/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.io.File

/**
 * Standalone Android command-line interface (CLI) entry point for Apktool.
 * Can be invoked directly via `/system/bin/app_process` in OpenManager's terminal,
 * Termex, or any unprivileged/privileged Android shell session:
 *
 * `app_process -Djava.class.path=base.apk /system/bin me.zhanghai.android.files.filelist.ApktoolCli "$@"`
 */
object ApktoolCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] in listOf("-h", "--help", "help", "?")) {
            printHelp()
            return
        }

        val command = args[0].lowercase()
        val restArgs = args.sliceArray(1 until args.size)

        try {
            when (command) {
                "-v", "--version", "version", "v" -> {
                    printVersion()
                }
                "d", "decode" -> {
                    runDecode(restArgs)
                }
                "b", "build" -> {
                    runBuild(restArgs)
                }
                "s", "sign" -> {
                    runSign(restArgs)
                }
                else -> {
                    System.err.println("Error: Unknown command '$command'")
                    printHelp()
                    System.exit(1)
                }
            }
        } catch (t: Throwable) {
            System.err.println("E: Apktool operation failed: ${t.message ?: t.javaClass.simpleName}")
            t.printStackTrace()
            System.exit(1)
        }
    }

    private fun printVersion() {
        println("OpenManager Apktool v${ApktoolEngine.VERSION} (Android ART Native Edition)")
        println("Smali & Baksmali v3.0.9, Dexlib2 API 34 resilient engine")
        println("AXML 32-bit Architecture Support (ARM64-v8a / ARMv7 / x86_64)")
    }

    private fun printHelp() {
        println("""
            OpenManager Apktool v${ApktoolEngine.VERSION} - Android ART Native Reverse Engineering CLI
            Usage: apktool <command> [options] <file_or_dir>

            Commands:
              d, decode <file.apk>      Decode APK to directory
              b, build <dir>            Build APK package from decoded directory
              s, sign <file.apk>        Sign APK with testkey or custom keystore
              v, version                Show Apktool version information
              h, help                   Show this help message

            Decode options:
              -o, --output <dir>        Output directory for decoded project
              -f, --force               Force overwrite existing output directory
              -s, --no-src              Do not disassemble DEX bytecode into smali
              -r, --no-res              Do not decode binary XML resources

            Build options:
              -o, --output <file.apk>   Output path for built APK
              -f, --force               Force overwrite existing APK file
              -s, --sign                Sign the APK automatically after building

            Sign options:
              -o, --output <file.apk>   Output path for signed APK
              -k, --keystore <file>     Path to custom KeyStore file (default: testkey)
              -p, --pass <password>     Password for KeyStore and key
              -a, --alias <name>        Key alias

            Examples:
              apktool d /sdcard/app.apk -o /sdcard/app_src
              apktool b /sdcard/app_src -o /sdcard/rebuilt.apk --sign
              apktool sign /sdcard/rebuilt.apk -o /sdcard/rebuilt_signed.apk
        """.trimIndent())
    }

    private fun runDecode(args: Array<String>) {
        var inputPath: String? = null
        var outputPath: String? = null
        var force = false
        var noSrc = false
        var noRes = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-o", "--output" -> {
                    if (i + 1 < args.size) {
                        outputPath = args[++i]
                    } else {
                        System.err.println("E: Missing value for $arg")
                        System.exit(1)
                    }
                }
                "-f", "--force" -> force = true
                "-s", "--no-src" -> noSrc = true
                "-r", "--no-res" -> noRes = true
                else -> {
                    if (arg.startsWith("-")) {
                        System.err.println("W: Unknown decode option: $arg")
                    } else if (inputPath == null) {
                        inputPath = arg
                    }
                }
            }
            i++
        }

        if (inputPath == null) {
            System.err.println("E: Input APK file not specified.")
            println("Usage: apktool d <file.apk> [-o <out_dir>] [-f] [-s] [-r]")
            System.exit(1)
        }

        val apkFile = File(inputPath)
        if (!apkFile.exists()) {
            System.err.println("E: File not found: ${apkFile.absolutePath}")
            System.exit(1)
        }

        val outDir = if (outputPath != null) {
            File(outputPath)
        } else {
            val baseName = apkFile.name.removeSuffix(".apk")
            File(apkFile.parentFile ?: File("."), baseName)
        }

        val options = ApktoolEngine.DecompileOptions(
            disassembleDex = !noSrc,
            decodeResources = !noRes,
            forceOverwrite = force
        )

        val listener = object : ApktoolEngine.ApktoolListener {
            override fun onProgress(message: String, detail: String, percentage: Int) {
                if (percentage >= 0) {
                    print("\rI: $message ($percentage%)  ")
                }
            }
            override fun onLog(message: String) {
                println("\n$message")
            }
        }

        val startTime = System.currentTimeMillis()
        ApktoolEngine.decompileApk(
            inputApk = apkFile,
            outputDir = outDir,
            options = options,
            listener = listener
        )
        val elapsed = System.currentTimeMillis() - startTime
        println("\nI: Decode finished in ${elapsed}ms -> ${outDir.absolutePath}")
    }

    private fun runBuild(args: Array<String>) {
        var inputPath: String? = null
        var outputPath: String? = null
        var force = false
        var signAfter = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-o", "--output" -> {
                    if (i + 1 < args.size) {
                        outputPath = args[++i]
                    } else {
                        System.err.println("E: Missing value for $arg")
                        System.exit(1)
                    }
                }
                "-f", "--force" -> force = true
                "-s", "--sign" -> signAfter = true
                else -> {
                    if (arg.startsWith("-")) {
                        System.err.println("W: Unknown build option: $arg")
                    } else if (inputPath == null) {
                        inputPath = arg
                    }
                }
            }
            i++
        }

        if (inputPath == null) {
            System.err.println("E: Project directory not specified.")
            println("Usage: apktool b <dir> [-o <out.apk>] [-f] [-s]")
            System.exit(1)
        }

        val projectDir = File(inputPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            System.err.println("E: Directory not found: ${projectDir.absolutePath}")
            System.exit(1)
        }

        val outApk = if (outputPath != null) {
            File(outputPath)
        } else {
            val distDir = File(projectDir, "dist").apply { mkdirs() }
            File(distDir, "${projectDir.name}.apk")
        }

        val options = ApktoolEngine.RecompileOptions(
            forceOverwrite = force,
            signAfterBuild = signAfter
        )

        val listener = object : ApktoolEngine.ApktoolListener {
            override fun onProgress(message: String, detail: String, percentage: Int) {
                if (percentage >= 0) {
                    print("\rI: $message ($percentage%)  ")
                }
            }
            override fun onLog(message: String) {
                println("\n$message")
            }
        }

        val startTime = System.currentTimeMillis()
        ApktoolEngine.recompileApk(
            projectDir = projectDir,
            outputApk = outApk,
            options = options,
            listener = listener
        )
        val elapsed = System.currentTimeMillis() - startTime
        println("\nI: Build finished in ${elapsed}ms -> ${outApk.absolutePath}")

        if (signAfter) {
            println("I: Signing built APK with testkey...")
            val signedApk = if (outApk.name.endsWith(".apk")) {
                File(outApk.parentFile, "${outApk.name.removeSuffix(".apk")}_signed.apk")
            } else {
                File(outApk.parentFile, "${outApk.name}_signed.apk")
            }
            runSignDirect(outApk, signedApk)
        }
    }

    private fun runSign(args: Array<String>) {
        var inputPath: String? = null
        var outputPath: String? = null

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-o", "--output" -> {
                    if (i + 1 < args.size) {
                        outputPath = args[++i]
                    } else {
                        System.err.println("E: Missing value for $arg")
                        System.exit(1)
                    }
                }
                else -> {
                    if (arg.startsWith("-")) {
                        System.err.println("W: Unknown sign option: $arg")
                    } else if (inputPath == null) {
                        inputPath = arg
                    }
                }
            }
            i++
        }

        if (inputPath == null) {
            System.err.println("E: Input APK not specified.")
            println("Usage: apktool sign <file.apk> [-o <out.apk>]")
            System.exit(1)
        }

        val inFile = File(inputPath)
        if (!inFile.exists()) {
            System.err.println("E: File not found: ${inFile.absolutePath}")
            System.exit(1)
        }

        val outFile = if (outputPath != null) {
            File(outputPath)
        } else {
            val base = inFile.name.removeSuffix(".apk")
            File(inFile.parentFile ?: File("."), "${base}_signed.apk")
        }

        runSignDirect(inFile, outFile)
    }

    private fun runSignDirect(inFile: File, outFile: File) {
        try {
            val (privateKey, cert) = ApkSignerHelper.getTestKeyPair()
            val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
                "CERT",
                privateKey,
                listOf(cert)
            ).build()

            val tempOut = File.createTempFile("apk_cli_signed_", ".apk")
            val builder = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inFile)
                .setOutputApk(tempOut)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)

            val signer = builder.build()
            signer.sign()

            outFile.parentFile?.mkdirs()
            tempOut.copyTo(outFile, overwrite = true)
            tempOut.delete()

            println("I: Successfully signed: ${outFile.absolutePath}")
        } catch (t: Throwable) {
            System.err.println("E: Failed to sign APK: ${t.message}")
            t.printStackTrace()
            System.exit(1)
        }
    }
}
