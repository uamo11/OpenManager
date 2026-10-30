/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.content.Context
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Engine for Java decompilation using JADX.
 * Supports single class decompilation, Smali-to-Java conversion,
 * and batch export of entire DEX or APK archives into Java sources.
 */
object JadxDecompilerEngine {

    /**
     * Converts a DEX descriptor (e.g., "Lcom/example/MyClass;") or path into a standard Java class name ("com.example.MyClass").
     */
    fun normalizeClassName(descriptorOrName: String): String {
        var clean = descriptorOrName.trim()
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length - 1)
        }
        return clean.replace('/', '.')
    }

    /**
     * Decompiles a single class from a DEX, APK, or JAR file into Java source code.
     */
    fun decompileClass(inputFile: File, classDescriptorOrName: String): String {
        val targetName = normalizeClassName(classDescriptorOrName)
        val args = JadxArgs().apply {
            isSkipResources = true
            isShowInconsistentCode = true
            inputFiles = listOf(inputFile)
        }

        return try {
            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                val javaClass: JavaClass? = decompiler.searchJavaClassByOrigFullName(targetName)
                    ?: decompiler.searchJavaClassOrItsParentByOrigFullName(targetName)
                    ?: decompiler.classes.find {
                        it.fullName == targetName ||
                            it.fullName.replace('$', '.') == targetName.replace('$', '.')
                    }
                    ?: decompiler.classesWithInners.find {
                        it.fullName == targetName ||
                            it.fullName.replace('$', '.') == targetName.replace('$', '.')
                    }

                if (javaClass == null) {
                    return "// [JADX] No se encontró la clase '$targetName' en ${inputFile.name}\n" +
                        "// Clases disponibles: ${decompiler.classes.take(5).joinToString { it.fullName }}"
                }

                javaClass.decompile()
                val code = javaClass.code
                if (!code.isNullOrBlank()) {
                    code
                } else {
                    "// [JADX] No se pudo generar código fuente Java para $targetName"
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            "// [JADX] Excepción durante la descompilación: ${e.message ?: e.javaClass.simpleName}\n"
        }
    }

    /**
     * Converts Smali source code directly into Java source code by assembling a temporary DEX
     * and running JADX decompiler over it.
     */
    fun decompileSmali(context: Context, smaliCode: String, classDescriptor: String = ""): String {
        val workDir = File(context.cacheDir, "jadx_smali_${System.currentTimeMillis()}").apply { mkdirs() }
        val smaliFile = File(workDir, "temp.smali")
        val outDexFile = File(workDir, "temp.dex")

        return try {
            smaliFile.writeText(smaliCode)
            val options = SmaliOptions().apply {
                apiLevel = 28
                outputDexFile = outDexFile.absolutePath
                verboseErrors = true
            }

            val assembled = Smali.assemble(options, listOf(smaliFile.absolutePath))
            if (!assembled || !outDexFile.exists() || outDexFile.length() == 0L) {
                return "// [JADX] Error de sintaxis Smali: no se pudo ensamblar el archivo temporal"
            }

            val args = JadxArgs().apply {
                isSkipResources = true
                isShowInconsistentCode = true
                inputFiles = listOf(outDexFile)
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                val targetName = if (classDescriptor.isNotEmpty()) normalizeClassName(classDescriptor) else ""
                val javaClass: JavaClass? = if (targetName.isNotEmpty()) {
                    decompiler.searchJavaClassByOrigFullName(targetName) ?: decompiler.classes.firstOrNull()
                } else {
                    decompiler.classes.firstOrNull()
                }

                if (javaClass != null) {
                    javaClass.decompile()
                    javaClass.code ?: "// [JADX] Código vacío"
                } else {
                    "// [JADX] No se encontró clase decompilable en el Smali proporcionado"
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            "// [JADX] Error al convertir Smali a Java: ${e.message}"
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Batch decompiles all classes from a DEX or APK file into a ZIP file of Java sources.
     *
     * @param inputFile Input .dex or .apk file.
     * @param outputZipFile Target .zip file for exported .java files.
     * @param isCancelled Optional cancellation check.
     * @param onProgress Callback (currentClassIndex, totalClasses, currentClassName).
     */
    fun decompileToZip(
        inputFile: File,
        outputZipFile: File,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((current: Int, total: Int, currentClass: String) -> Unit)? = null
    ): File {
        val args = JadxArgs().apply {
            isSkipResources = true
            isShowInconsistentCode = true
            inputFiles = listOf(inputFile)
        }

        JadxDecompiler(args).use { decompiler ->
            decompiler.load()
            val classes = decompiler.classes
            val total = classes.size

            ZipOutputStream(FileOutputStream(outputZipFile).buffered()).use { zos ->
                // Write Manifest
                val manifestEntry = ZipEntry("META-INF/MANIFEST.MF")
                zos.putNextEntry(manifestEntry)
                zos.write("Manifest-Version: 1.0\nCreated-By: OpenManager (JADX Decompiler)\n".toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                for ((index, javaClass) in classes.withIndex()) {
                    if (isCancelled?.invoke() == true) {
                        throw InterruptedException("JADX export cancelled by user")
                    }
                    val fullName = javaClass.fullName
                    if (fullName.isNullOrBlank()) continue
                    onProgress?.invoke(index + 1, total, fullName)

                    try {
                        javaClass.decompile()
                        val code = javaClass.code
                        if (!code.isNullOrBlank()) {
                            val entryPath = fullName.replace('.', '/') + ".java"
                            val entry = ZipEntry(entryPath)
                            zos.putNextEntry(entry)
                            zos.write(code.toByteArray(StandardCharsets.UTF_8))
                            zos.closeEntry()
                        }
                    } catch (e: Throwable) {
                        val entryPath = fullName.replace('.', '/') + ".java"
                        val entry = ZipEntry(entryPath)
                        zos.putNextEntry(entry)
                        val errorComment = "// [JADX] Error al descompilar clase $fullName: ${e.message}\n"
                        zos.write(errorComment.toByteArray(StandardCharsets.UTF_8))
                        zos.closeEntry()
                    }
                }
            }
        }
        return outputZipFile
    }

    /**
     * Batch decompiles all classes from a DEX or APK file directly into a directory of .java source files.
     * Preserves standard package hierarchy (e.g. com/example/MainActivity.java).
     *
     * @param inputFile Input .dex or .apk file.
     * @param outputDir Target directory for extracted .java files (e.g. OpenManager/ApkTool/<pkg>/decomp_java).
     * @param isCancelled Optional cancellation check called before each class.
     * @param onProgress Callback (currentClassIndex, totalClasses, currentClassName).
     */
    fun decompileToDirectory(
        inputFile: File,
        outputDir: File,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((current: Int, total: Int, currentClass: String) -> Unit)? = null
    ): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val args = JadxArgs().apply {
            isSkipResources = true
            isShowInconsistentCode = true
            inputFiles = listOf(inputFile)
        }

        JadxDecompiler(args).use { decompiler ->
            decompiler.load()
            val classes = decompiler.classes
            val total = classes.size

            for ((index, javaClass) in classes.withIndex()) {
                if (isCancelled?.invoke() == true) {
                    throw InterruptedException("JADX decompilation cancelled by user")
                }

                val fullName = javaClass.fullName
                if (fullName.isNullOrBlank()) continue
                onProgress?.invoke(index + 1, total, fullName)

                val relPath = fullName.replace('.', File.separatorChar) + ".java"
                val targetFile = File(outputDir, relPath)
                if (!targetFile.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)) {
                    continue
                }
                targetFile.parentFile?.mkdirs()

                try {
                    javaClass.decompile()
                    val code = javaClass.code
                    targetFile.writeText(code ?: "// [JADX] Código vacío\n", StandardCharsets.UTF_8)
                } catch (e: Throwable) {
                    targetFile.writeText("// [JADX] Error al descompilar clase $fullName: ${e.message}\n", StandardCharsets.UTF_8)
                }
            }
        }
        return outputDir
    }
}
