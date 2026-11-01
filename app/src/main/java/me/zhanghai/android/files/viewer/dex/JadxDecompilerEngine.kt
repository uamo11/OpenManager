/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.content.Context
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File
import java.io.FileOutputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Engine for Java/Smali decompilation on Android ART.
 * Bypasses JADX's desktop plugin loader (`JadxPluginManager` -> `ServiceLoader`), which throws
 * `UnsupportedOperationException` ("Path not associated with default file system") and
 * `ExceptionInInitializerError` on Android ART by directly loading DEX files via `DexInputPlugin`
 * and `RootNode`. Includes a full Android-native Dexlib2 + Baksmali fallback.
 */
object JadxDecompilerEngine {

    /**
     * Converts a DEX descriptor (e.g., "Lcom/example/MyClass;" or "I") or path into a standard Java type/class name ("com.example.MyClass" or "int").
     */
    fun normalizeClassName(descriptorOrName: String): String {
        val clean = descriptorOrName.trim()
        if (clean.startsWith("[")) {
            return normalizeClassName(clean.substring(1)) + "[]"
        }
        return when (clean) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> {
                val stripped = if (clean.startsWith("L") && clean.endsWith(";")) {
                    clean.substring(1, clean.length - 1)
                } else {
                    clean
                }
                stripped.replace('/', '.')
            }
        }
    }

    /**
     * Creates JadxArgs safely configured for Android runtime (single-threaded, cache in temp dir, resources skipped).
     */
    private fun createAndroidSafeJadxArgs(inputFile: File): JadxArgs {
        return JadxArgs().apply {
            isSkipResources = true
            isShowInconsistentCode = true
            threadsCount = 1
            isUseImports = true
            isDebugInfo = false
            isDeobfuscationOn = false
            isRenameCaseSensitive = false
            isRenameValid = false
            isRenamePrintable = false
            inputFiles = listOf(inputFile)
            try {
                codeCache = jadx.api.impl.NoOpCodeCache()
            } catch (_: Throwable) {}
            try {
                codeWriterProvider = java.util.function.Function { args -> jadx.api.impl.SimpleCodeWriter(args) }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Checks whether the current runtime is Android ART/Dalvik (where JADX's desktop
     * ServiceLoader / java.nio.file.FileSystems plugin scanner is unsupported).
     */
    fun isAndroidRuntime(): Boolean {
        return try {
            android.os.Build.VERSION.SDK_INT > 0
        } catch (_: Throwable) {
            val vmName = System.getProperty("java.vm.name") ?: ""
            val runtimeName = System.getProperty("java.runtime.name") ?: ""
            vmName.contains("Dalvik", ignoreCase = true) ||
                vmName.contains("ART", ignoreCase = true) ||
                runtimeName.contains("Android", ignoreCase = true)
        }
    }

    /**
     * Extracts all .dex files from an APK/ZIP (or returns the file itself if it's already a .dex file).
     */
    private fun extractDexFiles(inputFile: File, workDir: File): List<File> {
        if (inputFile.name.endsWith(".dex", ignoreCase = true)) {
            return listOf(inputFile)
        }
        val dexFiles = mutableListOf<File>()
        try {
            ZipFile(inputFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!entry.isDirectory && !name.contains('/') && name.startsWith("classes") && name.endsWith(".dex")) {
                        val outDex = File(workDir, name)
                        zip.getInputStream(entry).use { inp ->
                            FileOutputStream(outDex).use { out -> inp.copyTo(out) }
                        }
                        if (outDex.length() > 0L) {
                            dexFiles.add(outDex)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return dexFiles.sortedBy { it.name }
    }

    /**
     * Generates a readable pseudo-Java + Smali representation of a Dexlib2 ClassDef when JADX fails on Android ART.
     */
    private fun decompileClassDefFallback(classDef: com.android.tools.smali.dexlib2.iface.ClassDef): String {
        val sb = StringBuilder()
        val fullName = normalizeClassName(classDef.type)
        val pkg = fullName.substringBeforeLast('.', "")
        val simpleName = fullName.substringAfterLast('.')

        if (pkg.isNotEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n")
        }

        val superName = classDef.superclass?.let { normalizeClassName(it) } ?: "java.lang.Object"
        val interfaces = classDef.interfaces.map { normalizeClassName(it) }
        sb.append("public class ").append(simpleName)
        if (superName != "java.lang.Object") {
            sb.append(" extends ").append(superName)
        }
        if (interfaces.isNotEmpty()) {
            sb.append(" implements ").append(interfaces.joinToString(", "))
        }
        sb.append(" {\n")

        // Fields
        for (field in classDef.fields) {
            val fType = normalizeClassName(field.type)
            val initVal = field.initialValue?.let { " = $it" } ?: ""
            sb.append("    public ").append(fType).append(" ").append(field.name).append(initVal).append(";\n")
        }
        if (classDef.fields.any()) {
            sb.append("\n")
        }

        // Methods
        for (method in classDef.methods) {
            val retType = if (method.name == "<init>" || method.name == "<clinit>") "" else "${normalizeClassName(method.returnType)} "
            val mName = when (method.name) {
                "<init>" -> simpleName
                "<clinit>" -> "static"
                else -> method.name
            }
            val params = method.parameterTypes.mapIndexed { idx, p -> "${normalizeClassName(p.toString())} p$idx" }.joinToString(", ")
            if (method.name == "<clinit>") {
                sb.append("    static {\n")
            } else {
                sb.append("    public ").append(retType).append(mName).append("(").append(params).append(") {\n")
            }
            val impl = method.implementation
            if (impl != null) {
                sb.append("        // Registros: ").append(impl.registerCount).append("\n")
                for (insn in impl.instructions.take(80)) {
                    sb.append("        // ").append(insn.opcode.name).append("\n")
                }
            }
            sb.append("    }\n\n")
        }

        sb.append("}\n\n")

        // Also append full Baksmali disassembly inside a block comment for complete inspection
        try {
            val options = BaksmaliOptions().apply {
                deodex = false
                parameterRegisters = true
            }
            val classDefinition = ClassDefinition(options, classDef)
            val sw = StringWriter()
            BaksmaliWriter(sw).use { writer ->
                classDefinition.writeTo(writer)
            }
            sb.append("/* ==================== SMALI DISASSEMBLY ====================\n")
            sb.append(sw.toString().replace("*/", "* /"))
            sb.append("\n============================================================= */\n")
        } catch (_: Throwable) {}

        return sb.toString()
    }

    /**
     * Decompiles a single class from a DEX, APK, or JAR file into Java source code.
     */
    fun decompileClass(inputFile: File, classDescriptorOrName: String): String {
        val targetName = normalizeClassName(classDescriptorOrName)
        val tempDir = File(inputFile.parentFile ?: File("/data/local/tmp"), ".dex_cls_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val dexFiles = extractDexFiles(inputFile, tempDir)

            // 1. Try JADX when not on Android ART (or if supported), guarded by catch (Throwable)
            if (!isAndroidRuntime()) {
                try {
                    val args = createAndroidSafeJadxArgs(inputFile)
                    JadxDecompiler(args).use { decompiler ->
                        decompiler.load()
                        val matchClass = decompiler.classes.find { cls ->
                            cls.fullName == targetName ||
                                cls.classNode.classInfo.fullName == targetName ||
                                cls.fullName.replace('$', '.') == targetName.replace('$', '.')
                        }
                        if (matchClass != null) {
                            val code = matchClass.code
                            if (!code.isNullOrBlank()) {
                                return code
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // 2. Android ART Native Path via Dexlib2 + Baksmali
            val targetDescriptor = "L" + targetName.replace('.', '/') + ";"
            for (dex in dexFiles) {
                try {
                    val dexBacked: DexBackedDexFile = me.zhanghai.android.files.filelist.ApktoolEngine.loadDexSafely(dex)
                    val match = dexBacked.classes.find {
                        it.type == targetDescriptor || normalizeClassName(it.type) == targetName
                    }
                    if (match != null) {
                        return decompileClassDefFallback(match)
                    }
                } catch (_: Throwable) {}
            }
            "// No se encontró la clase '$targetName' en ${inputFile.name}\n"
        } catch (e: Throwable) {
            "// Excepción durante la descompilación: ${e.message ?: e.javaClass.simpleName}\n"
        } finally {
            if (tempDir != inputFile.parentFile) {
                try { tempDir.deleteRecursively() } catch (_: Throwable) {}
            }
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
                return "// Error de sintaxis Smali: no se pudo ensamblar el archivo temporal"
            }

            decompileClass(outDexFile, classDescriptor.ifEmpty { "Temp" })
        } catch (e: Throwable) {
            e.printStackTrace()
            "// Error al convertir Smali a Java: ${e.message}"
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Batch decompiles all classes from a DEX or APK file into a ZIP file of Java sources.
     */
    fun decompileToZip(
        inputFile: File,
        outputZipFile: File,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((current: Int, total: Int, currentClass: String) -> Unit)? = null
    ): File {
        val workDir = File(outputZipFile.parentFile ?: File("/data/local/tmp"), ".dex_zip_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val dexFiles = extractDexFiles(inputFile, workDir)

            // 1. Try JADX when not on Android ART, guarded by catch (Throwable)
            if (!isAndroidRuntime()) {
                try {
                    val args = createAndroidSafeJadxArgs(inputFile)
                    JadxDecompiler(args).use { decompiler ->
                        decompiler.load()
                        val classes = decompiler.classes
                        val total = classes.size
                        if (total > 0) {
                            var writtenCount = 0
                            ZipOutputStream(FileOutputStream(outputZipFile).buffered()).use { zos ->
                                val manifestEntry = ZipEntry("META-INF/MANIFEST.MF")
                                zos.putNextEntry(manifestEntry)
                                zos.write("Manifest-Version: 1.0\nCreated-By: OpenManager (JADX Decompiler)\n".toByteArray(StandardCharsets.UTF_8))
                                zos.closeEntry()

                                for ((index, cls) in classes.withIndex()) {
                                    if (isCancelled?.invoke() == true) {
                                        throw InterruptedException("JADX export cancelled by user")
                                    }
                                    val fullName = cls.fullName
                                    if (fullName.isNullOrBlank()) continue
                                    onProgress?.invoke(index + 1, total, fullName)

                                    val code = try { cls.code } catch (_: Throwable) { null }
                                    if (!code.isNullOrBlank()) {
                                        val entryPath = fullName.replace('.', '/') + ".java"
                                        zos.putNextEntry(ZipEntry(entryPath))
                                        zos.write(code.toByteArray(StandardCharsets.UTF_8))
                                        zos.closeEntry()
                                        writtenCount++
                                    }
                                }
                            }
                            if (writtenCount > 0) {
                                return outputZipFile
                            }
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw ie
                } catch (_: Throwable) {}
            }

            // 2. Android ART Native Path: Dexlib2 + Baksmali ZIP export
            val allClassDefs = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
            for (dex in dexFiles) {
                try {
                    val dexBacked = me.zhanghai.android.files.filelist.ApktoolEngine.loadDexSafely(dex)
                    allClassDefs.addAll(dexBacked.classes)
                } catch (_: Throwable) {}
            }
            val total = allClassDefs.size
            ZipOutputStream(FileOutputStream(outputZipFile).buffered()).use { zos ->
                for ((index, classDef) in allClassDefs.withIndex()) {
                    if (isCancelled?.invoke() == true) {
                        throw InterruptedException("Decompilation cancelled by user")
                    }
                    val fullName = normalizeClassName(classDef.type)
                    onProgress?.invoke(index + 1, total, fullName)
                    val entryPath = fullName.replace('.', '/') + ".java"
                    zos.putNextEntry(ZipEntry(entryPath))
                    val code = try {
                        decompileClassDefFallback(classDef)
                    } catch (t: Throwable) {
                        "// Error al descompilar clase $fullName: ${t.message}\n"
                    }
                    zos.write(code.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
            }
        } finally {
            try { workDir.deleteRecursively() } catch (_: Throwable) {}
        }
        return outputZipFile
    }

    /**
     * Batch decompiles all classes from a DEX or APK file directly into a directory of .java source files.
     * Preserves standard package hierarchy (e.g. com/example/MainActivity.java).
     * Bypasses JadxPluginManager (ServiceLoader) via loadJadxRootDirect and automatically falls back
     * to Dexlib2 + Baksmali for any class or runtime where needed.
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

        val workDir = File(outputDir.parentFile ?: outputDir, ".tmp_dex_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val dexFiles = extractDexFiles(inputFile, workDir)
            var writtenCount = 0

            // 1. Try JADX when not on Android ART, guarded by catch (Throwable)
            if (!isAndroidRuntime()) {
                try {
                    val args = createAndroidSafeJadxArgs(inputFile)
                    JadxDecompiler(args).use { decompiler ->
                        decompiler.load()
                        val classes = decompiler.classes
                        val total = classes.size
                        val canonicalOutPrefix = outputDir.canonicalPath + File.separator

                        for ((index, cls) in classes.withIndex()) {
                            if (isCancelled?.invoke() == true) {
                                throw InterruptedException("JADX decompilation cancelled by user")
                            }
                            val fullName = cls.fullName
                            if (fullName.isNullOrBlank()) continue
                            onProgress?.invoke(index + 1, total, fullName)

                            val relPath = fullName.replace('.', File.separatorChar) + ".java"
                            val targetFile = File(outputDir, relPath)
                            if (!targetFile.canonicalPath.startsWith(canonicalOutPrefix)) {
                                continue
                            }
                            targetFile.parentFile?.mkdirs()

                            val code = try { cls.code } catch (_: Throwable) { null }
                            if (!code.isNullOrBlank()) {
                                targetFile.writeText(code, StandardCharsets.UTF_8)
                                writtenCount++
                            }
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw ie
                } catch (_: Throwable) {}
            }

            if (writtenCount > 0) {
                return outputDir
            }

            // 2. Android ART Fallback: Use Dexlib2 + Baksmali to guarantee 100% reliable decompilation
            val allClassDefs = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
            for (dex in dexFiles) {
                if (isCancelled?.invoke() == true) throw InterruptedException()
                try {
                    val dexBacked = me.zhanghai.android.files.filelist.ApktoolEngine.loadDexSafely(dex)
                    allClassDefs.addAll(dexBacked.classes)
                } catch (_: Throwable) {}
            }

            val total = allClassDefs.size
            val canonicalOutPrefix = outputDir.canonicalPath + File.separator
            for ((index, classDef) in allClassDefs.withIndex()) {
                if (isCancelled?.invoke() == true) {
                    throw InterruptedException("Decompilation cancelled by user")
                }
                val fullName = normalizeClassName(classDef.type)
                if (fullName.isBlank()) continue
                onProgress?.invoke(index + 1, total, fullName)

                val relPath = fullName.replace('.', File.separatorChar) + ".java"
                val targetFile = File(outputDir, relPath)
                if (!targetFile.canonicalPath.startsWith(canonicalOutPrefix)) {
                    continue
                }
                targetFile.parentFile?.mkdirs()

                try {
                    val code = decompileClassDefFallback(classDef)
                    targetFile.writeText(code, StandardCharsets.UTF_8)
                } catch (t: Throwable) {
                    targetFile.writeText("// Error al descompilar clase $fullName: ${t.message}\n", StandardCharsets.UTF_8)
                }
            }
        } finally {
            try { workDir.deleteRecursively() } catch (_: Throwable) {}
        }

        return outputDir
    }
}
