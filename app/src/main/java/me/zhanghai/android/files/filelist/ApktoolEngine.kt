/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import me.zhanghai.android.files.file.AxmlDecoder
import me.zhanghai.android.files.file.AxmlEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Resilient, multi-platform Apktool engine for Android.
 * Runs seamlessly both in-process (via ApktoolExecutionService) and standalone (via ApktoolCli / app_process).
 */
object ApktoolEngine {

    const val VERSION = "2.9.3"

    data class DecompileOptions(
        val disassembleDex: Boolean = true,
        val decodeResources: Boolean = true,
        val forceOverwrite: Boolean = false,
        val apiLevel: Int = 34
    )

    data class RecompileOptions(
        val forceOverwrite: Boolean = false,
        val signAfterBuild: Boolean = false,
        val apiLevel: Int = 34
    )

    interface ApktoolListener {
        fun onProgress(message: String, detail: String, percentage: Int)
        fun onLog(message: String)
    }

    /**
     * Resolves the highest supported Opcodes instance without throwing UnsupportedOperationException on modern DEX versions.
     */
    fun getSafeOpcodes(preferredApi: Int = 34): Opcodes {
        val candidateApis = intArrayOf(preferredApi, 34, 30, 28, 26, 21, 19)
        for (api in candidateApis) {
            try {
                return Opcodes.forApi(api)
            } catch (_: Throwable) {}
        }
        return try {
            Opcodes.getDefault()
        } catch (_: Throwable) {
            Opcodes.forApi(20)
        }
    }

    /**
     * Safely loads a DEX file using descending API levels (34 down to 19) to guarantee
     * compatibility with all DEX versions (DEX 035 up to 040+).
     */
    fun loadDexSafely(dexFile: File): DexBackedDexFile {
        val apisToTry = intArrayOf(34, 33, 30, 28, 26, 24, 21, 19)
        for (api in apisToTry) {
            try {
                return DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(api))
            } catch (_: Throwable) {}
        }
        return DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
    }

    /**
     * Decompiles an APK archive into a structured project directory:
     * - AndroidManifest.xml (decoded text + .orig binary)
     * - res/ (decoded binary XMLs + .orig binaries, raw assets)
     * - classes*.dex -> smali/, smali_classes2/, etc.
     * - apktool.yml metadata
     */
    fun decompileApk(
        inputApk: File,
        outputDir: File,
        options: DecompileOptions = DecompileOptions(),
        isCancelled: () -> Boolean = { false },
        listener: ApktoolListener? = null
    ) {
        if (!inputApk.exists()) {
            throw IllegalArgumentException("Input APK file not found: ${inputApk.absolutePath}")
        }

        if (outputDir.exists() && options.forceOverwrite) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()
        val canonicalProjectDir = outputDir.canonicalPath + File.separator

        listener?.onLog("I: Using Apktool $VERSION on ${inputApk.name}")
        listener?.onProgress("Contando archivos del APK...", "", -1)

        // Count total entries for progress calculation
        var totalEntries = 0
        try {
            FileInputStream(inputApk).use { inStream ->
                ZipInputStream(inStream).use { zipIn ->
                    while (zipIn.nextEntry != null) {
                        totalEntries++
                    }
                }
            }
        } catch (_: Throwable) {}

        if (isCancelled()) throw InterruptedException()

        var currentEntry = 0
        val buffer = ByteArray(65536)

        FileInputStream(inputApk).use { inStream ->
            ZipInputStream(inStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (isCancelled()) throw InterruptedException()

                    currentEntry++
                    val name = entry.name
                    val pct = if (totalEntries > 0) (currentEntry * 100) / totalEntries else -1
                    if (currentEntry % 15 == 0 || currentEntry == totalEntries) {
                        listener?.onProgress("Extrayendo recursos ($pct%)", name, pct)
                    }

                    if (!entry.isDirectory && !name.startsWith("META-INF/")) {
                        try {
                            val targetFile = File(outputDir, name).canonicalFile
                            if (!targetFile.canonicalPath.startsWith(canonicalProjectDir)) {
                                // Defensive Zip Slip protection
                                zipIn.closeEntry()
                                entry = zipIn.nextEntry
                                continue
                            }
                            targetFile.parentFile?.mkdirs()

                            val baos = ByteArrayOutputStream()
                            var bytesRead: Int
                            while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                                if (isCancelled()) throw InterruptedException()
                                baos.write(buffer, 0, bytesRead)
                            }
                            val entryBytes = baos.toByteArray()

                            if (options.decodeResources && name == "AndroidManifest.xml" && AxmlDecoder.isAxml(entryBytes)) {
                                val decoded = try {
                                    AxmlDecoder.decode(entryBytes)
                                } catch (_: Throwable) {
                                    null
                                }
                                if (decoded != null) {
                                    FileOutputStream(targetFile).use { it.write(decoded.toByteArray(StandardCharsets.UTF_8)) }
                                    val origFile = File(outputDir, "AndroidManifest.xml.orig")
                                    FileOutputStream(origFile).use { it.write(entryBytes) }
                                } else {
                                    FileOutputStream(targetFile).use { it.write(entryBytes) }
                                }
                            } else if (options.decodeResources && name.startsWith("res/") && name.endsWith(".xml") && AxmlDecoder.isAxml(entryBytes)) {
                                val decoded = try {
                                    AxmlDecoder.decode(entryBytes)
                                } catch (_: Throwable) {
                                    null
                                }
                                if (decoded != null) {
                                    FileOutputStream(targetFile).use { it.write(decoded.toByteArray(StandardCharsets.UTF_8)) }
                                    val origFile = File(outputDir, "$name.orig").canonicalFile
                                    if (origFile.canonicalPath.startsWith(canonicalProjectDir)) {
                                        origFile.parentFile?.mkdirs()
                                        FileOutputStream(origFile).use { it.write(entryBytes) }
                                    }
                                } else {
                                    FileOutputStream(targetFile).use { it.write(entryBytes) }
                                }
                            } else {
                                FileOutputStream(targetFile).use { it.write(entryBytes) }
                            }
                        } catch (ie: InterruptedException) {
                            throw ie
                        } catch (entryErr: Throwable) {
                            entryErr.printStackTrace()
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }

        if (isCancelled()) throw InterruptedException()

        // Generate apktool.yml metadata
        val yml = """
            version: $VERSION
            apkFileName: ${inputApk.name}
            isFrameworkApk: false
            packageInfo:
              forcedPackageId: '127'
            versionInfo:
              versionCode: '1'
              versionName: '1.0'
            compressionType: false
            doNotCompress:
            - arsc
            - png
            - webp
            - jpg
            - ogg
            - mp3
        """.trimIndent()
        val ymlFile = File(outputDir, "apktool.yml")
        FileOutputStream(ymlFile).use { it.write(yml.toByteArray(StandardCharsets.UTF_8)) }

        // Disassemble classes*.dex into smali/ directories if requested
        if (options.disassembleDex) {
            val dexFilesInProject = outputDir.listFiles { f -> f.isFile && f.name.startsWith("classes") && f.name.endsWith(".dex") } ?: emptyArray()
            dexFilesInProject.sortBy { it.name }

            for (dexFile in dexFilesInProject) {
                if (isCancelled()) throw InterruptedException()
                val smaliDirName = if (dexFile.name == "classes.dex") "smali" else "smali_${dexFile.name.removeSuffix(".dex")}"
                val smaliOutDir = File(outputDir, smaliDirName)
                smaliOutDir.mkdirs()

                listener?.onLog("I: Disassembling ${dexFile.name} to $smaliDirName...")
                listener?.onProgress("Desensamblando ${dexFile.name} a smali...", "", -1)

                try {
                    val dexBacked = loadDexSafely(dexFile)
                    val baksmaliOpts = BaksmaliOptions().apply {
                        deodex = false
                        parameterRegisters = true
                    }
                    val totalClasses = dexBacked.classes.size
                    var classCount = 0

                    for (classDef in dexBacked.classes) {
                        if (isCancelled()) throw InterruptedException()
                        classCount++
                        val cType = classDef.type
                        val relPath = cType.removePrefix("L").removeSuffix(";").replace('/', File.separatorChar) + ".smali"
                        val targetSmaliFile = File(smaliOutDir, relPath)
                        targetSmaliFile.parentFile?.mkdirs()

                        try {
                            val sw = StringWriter()
                            BaksmaliWriter(sw).use { w ->
                                ClassDefinition(baksmaliOpts, classDef).writeTo(w)
                            }
                            targetSmaliFile.writeText(sw.toString(), StandardCharsets.UTF_8)
                        } catch (classEx: Throwable) {
                            // Defensive recovery: write stub for obfuscated/corrupted class definition
                            val fallbackContent = """
                                # Failed to disassemble class $cType: ${classEx.message}
                                .class public $cType
                                .super Ljava/lang/Object;
                            """.trimIndent()
                            targetSmaliFile.writeText(fallbackContent, StandardCharsets.UTF_8)
                        }
                    }
                    listener?.onLog("I: Successfully disassembled $classCount classes from ${dexFile.name}")
                } catch (dexEx: Throwable) {
                    listener?.onLog("W: Failed to fully disassemble ${dexFile.name}: ${dexEx.message}")
                }
            }
        }

        listener?.onLog("I: Done! Project created at: ${outputDir.absolutePath}")
    }

    /**
     * Reassembles smali/ directories into DEX files and compiles the project into an APK archive.
     */
    fun recompileApk(
        projectDir: File,
        outputApk: File,
        options: RecompileOptions = RecompileOptions(),
        isCancelled: () -> Boolean = { false },
        listener: ApktoolListener? = null
    ) {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            throw IllegalArgumentException("Project directory not found: ${projectDir.absolutePath}")
        }

        outputApk.parentFile?.mkdirs()
        listener?.onLog("I: Using Apktool $VERSION to recompile ${projectDir.name}")
        listener?.onProgress("Recompilando proyecto APK...", "", -1)

        // 1. Reassemble smali directories into classes*.dex
        val smaliDirs = projectDir.listFiles { f -> f.isDirectory && f.name.startsWith("smali") } ?: emptyArray()
        smaliDirs.sortBy { it.name }

        for (sDir in smaliDirs) {
            if (isCancelled()) throw InterruptedException()
            val dexName = if (sDir.name == "smali") "classes.dex" else "${sDir.name.removePrefix("smali_")}.dex"
            val targetDexFile = File(projectDir, dexName)
            val smaliFiles = mutableListOf<String>()
            sDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach {
                smaliFiles.add(it.absolutePath)
            }

            if (smaliFiles.isNotEmpty()) {
                listener?.onLog("I: Assembling $dexName (${smaliFiles.size} smali files)...")
                listener?.onProgress("Ensamblando $dexName (${smaliFiles.size} archivos smali)...", "", -1)

                val apiLevels = intArrayOf(options.apiLevel, 34, 30, 28)
                var assembleSuccess = false
                var lastAssembleError: Throwable? = null

                for (api in apiLevels) {
                    try {
                        val smaliOpts = SmaliOptions().apply {
                            apiLevel = api
                            outputDexFile = targetDexFile.absolutePath
                            verboseErrors = false
                        }
                        if (Smali.assemble(smaliOpts, smaliFiles)) {
                            assembleSuccess = true
                            listener?.onLog("I: Successfully assembled $dexName using API $api")
                            break
                        }
                    } catch (t: Throwable) {
                        lastAssembleError = t
                    }
                }

                if (!assembleSuccess && lastAssembleError != null) {
                    listener?.onLog("W: Smali assembly warning for $dexName: ${lastAssembleError.message}")
                }
            }
        }

        if (isCancelled()) throw InterruptedException()

        // 2. Count files for progress tracking
        var totalFiles = 0
        fun countFiles(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (!f.name.startsWith("smali")) {
                        countFiles(f)
                    }
                } else {
                    val name = f.name
                    if (name != "apktool.yml" && !name.endsWith(".orig")) {
                        totalFiles++
                    }
                }
            }
        }
        try { countFiles(projectDir) } catch (_: Throwable) {}

        // 3. Packaging into output APK
        var currentFile = 0
        val baseCanonical = projectDir.canonicalPath
        FileOutputStream(outputApk).use { outStream ->
            ZipOutputStream(outStream).use { zipOut ->
                fun addDirectoryToZip(currentDir: File) {
                    val list = currentDir.listFiles() ?: return
                    for (file in list) {
                        if (isCancelled()) throw InterruptedException()

                        val relPath = file.canonicalPath.removePrefix(baseCanonical).trimStart('/', '\\').replace('\\', '/')
                        if (file.isDirectory) {
                            if (!file.name.startsWith("smali")) {
                                addDirectoryToZip(file)
                            }
                        } else {
                            if (relPath == "apktool.yml" || relPath.endsWith(".orig") || relPath.startsWith("smali/") || relPath.startsWith("smali\\")) {
                                continue
                            }

                            currentFile++
                            val pct = if (totalFiles > 0) (currentFile * 100) / totalFiles else -1
                            if (currentFile % 20 == 0 || currentFile == totalFiles) {
                                listener?.onProgress("Empaquetando APK ($pct%)", relPath, pct)
                            }

                            var dataBytes: ByteArray
                            val origFile = File(currentDir, "${file.name}.orig")
                            if (origFile.exists()) {
                                val currentText = try { file.readText(StandardCharsets.UTF_8) } catch (_: Throwable) { "" }
                                val origBytes = origFile.readBytes()
                                val origText = if (AxmlDecoder.isAxml(origBytes)) {
                                    try { AxmlDecoder.decode(origBytes) } catch (_: Throwable) { null }
                                } else {
                                    origBytes.toString(StandardCharsets.UTF_8)
                                }
                                dataBytes = if (currentText == origText || origText == null) {
                                    origBytes
                                } else {
                                    file.readBytes()
                                }
                            } else {
                                dataBytes = file.readBytes()
                            }

                            val entry = ZipEntry(relPath)
                            val lower = relPath.lowercase()
                            val isStored = lower.endsWith(".arsc") || lower.endsWith(".png") ||
                                lower.endsWith(".jpg") || lower.endsWith(".webp") ||
                                lower.endsWith(".ogg") || lower.endsWith(".mp3")

                            if (isStored) {
                                entry.method = ZipEntry.STORED
                                entry.size = dataBytes.size.toLong()
                                entry.compressedSize = dataBytes.size.toLong()
                                val crc = CRC32()
                                crc.update(dataBytes)
                                entry.crc = crc.value
                            } else {
                                entry.method = ZipEntry.DEFLATED
                            }

                            zipOut.putNextEntry(entry)
                            zipOut.write(dataBytes)
                            zipOut.closeEntry()
                        }
                    }
                }

                addDirectoryToZip(projectDir)
                zipOut.finish()
            }
        }

        listener?.onLog("I: Built APK: ${outputApk.absolutePath} (${outputApk.length()} bytes)")
    }
}
