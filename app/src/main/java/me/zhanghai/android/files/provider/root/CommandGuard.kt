/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

/**
 * Decides what to do with a destructive shell command (rm, rmdir, unlink, find -delete, dd to a
 * block device, mkfs...) intercepted in an OpenManager terminal.
 *
 * Paths are resolved against the shell's working directory and compared segment by segment, so
 * `/sdcard/Backup/system/photo.jpg` is user data while `cd / && rm -rf *` (which the shell expands
 * to `/acct /apex /bin ...`) is caught. Pure Kotlin: no Android types, fully unit tested.
 */
object CommandGuard {

    enum class Zone {
        /** "/" itself. */
        ROOT,
        /** A top-level directory not covered below (/data, /mnt, /cache...): wiping it is fatal. */
        ROOT_CHILD,
        /** Read-only OS partitions and their aliases. */
        SYSTEM,
        /** Boot chain, partitions, root manager and system state: breaking them bricks the boot. */
        BOOT_CRITICAL,
        /** The whole internal storage or an SD card root. */
        USER_STORAGE_ROOT,
        /** Files inside user storage or /data/local/tmp. */
        USER,
        /** /data/data/<pkg>, /data/user/N/<pkg>. */
        APP_PRIVATE,
        OTHER
    }

    enum class Kind {
        DELETE,
        BLOCK_DEVICE_WRITE,
        /** mv, cp into, chmod/chown/chcon, ln, truncate, mount: change files without deleting them. */
        MODIFY,
        /** insmod / modprobe / rmmod: code loaded into (or removed from) the running kernel. */
        KERNEL_MODULE
    }

    /** Who runs the command: without root Android itself refuses any change to the OS. */
    enum class Privilege { USER, ROOT }

    enum class Verdict { ALLOW, ASK, BLOCK }

    enum class Reason {
        POLICY_ALLOW,
        POLICY_ASK,
        POLICY_BLOCK,
        /** Dead Man: the command would wipe the OS, the boot chain or all user data. */
        DEAD_MAN,
        /** DFRoot: the command would modify a partition or boot file that DirtyFlag needs to boot. */
        DFROOT,
        /** Not a destructive form of the command (e.g. `find` without -delete). */
        NOT_DESTRUCTIVE,
        /** Without root the system is protected by Android: nothing to ask. */
        UNPRIVILEGED,
        /** It would delete a whole storage or thousands of files at once. */
        MASS_DELETE,
        /** Loading or unloading a kernel module: the user sees what the module is first. */
        KERNEL_MODULE,
        /** The module was built for another kernel or CPU: loading it can freeze the phone. */
        KERNEL_MISMATCH
    }

    /**
     * ASK/ALLOW/BLOCK: the user's choice for this script. DEFAULT: no choice made (typed commands,
     * scripts without a setting): only the automatic protections apply.
     */
    enum class Policy { ASK, ALLOW, BLOCK, DEFAULT }

    /** Files at or above this count make a deletion "massive" (it asks even without root). */
    const val MASS_DELETE_THRESHOLD = 5000

    data class Target(val path: String, val zone: Zone)

    data class Analysis(
        val command: String,
        val kind: Kind,
        val targets: List<Target>,
        val recursive: Boolean,
        val destructive: Boolean,
        /** insmod/modprobe: the module path or name; `-f` skips the kernel's own checks. */
        val module: String? = null,
        val forced: Boolean = false
    ) {
        val catastrophicTargets: List<Target>
            get() = targets.filter {
                if (kind == Kind.MODIFY) isCatastrophicChange(it.zone, recursive, command) else isCatastrophic(it.zone, recursive)
            }

        /** Top of internal storage, an SD card or one of its main folders (DCIM, Download...). */
        val touchesStorageRootRecursively: Boolean
            get() = kind == Kind.DELETE && recursive && targets.any {
                it.zone == Zone.USER_STORAGE_ROOT || isMainUserFolder(it.path)
            }
    }

    /**
     * What the app knows about a kernel module before it is loaded (see KernelModuleInspector).
     * [problem] set means the module cannot work on this kernel.
     */
    data class ModuleCheck(val problem: String?, val touchesPartitions: Boolean)

    data class Decision(val verdict: Verdict, val reason: Reason, val analysis: Analysis)

    private val SYSTEM_ROOTS = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/oem", "/apex",
        "/vendor_dlkm", "/odm_dlkm", "/system_dlkm", "/sbin", "/bin", "/etc", "/lib", "/lib64",
        "/linkerconfig", "/dev", "/proc", "/sys", "/acct", "/config", "/postinstall", "/debug_ramdisk"
    )

    private val BOOT_CRITICAL_ROOTS = listOf(
        "/dev/block", "/data/adb", "/data/system", "/data/system_ce", "/data/system_de",
        "/data/misc", "/data/vendor", "/data/unencrypted", "/metadata", "/persist", "/mnt/vendor/persist",
        "/efs", "/boot", "/recovery", "/cache/recovery"
    )

    private val BOOT_CRITICAL_FILES = listOf("/init", "/default.prop", "/prop.default", "/init.rc")

    private val USER_STORAGE_ROOTS = listOf(
        "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0", "/storage/self",
        "/storage/self/primary", "/data/media", "/data/media/0", "/mnt/sdcard", "/mnt/user"
    )

    private val USER_ROOTS = listOf("/sdcard", "/storage", "/data/media", "/data/local/tmp", "/mnt/sdcard")

    private val SD_CARD_ROOT = Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
    private val MY_PARTITION = Regex("^/my_[a-z_]+(/.*)?$")
    private val FSTAB = Regex("^/(fstab\\.[^/]*|(system|vendor|odm)/etc/fstab\\.[^/]*)$")

    private fun isUnder(path: String, root: String): Boolean =
        path == root || path.startsWith("$root/")

    /** Absolute, normalized path; "." and ".." resolved lexically (never touches the disk). */
    fun normalize(cwd: String, arg: String): String {
        val raw = if (arg.startsWith("/")) arg else "${cwd.ifEmpty { "/" }}/$arg"
        val parts = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun classify(path: String): Zone {
        if (path == "/") return Zone.ROOT
        if (BOOT_CRITICAL_FILES.any { path == it } || FSTAB.matches(path) ||
            BOOT_CRITICAL_ROOTS.any { isUnder(path, it) }
        ) {
            return Zone.BOOT_CRITICAL
        }
        if (SYSTEM_ROOTS.any { isUnder(path, it) } || MY_PARTITION.matches(path)) return Zone.SYSTEM
        if (USER_STORAGE_ROOTS.any { path == it } || SD_CARD_ROOT.matches(path)) return Zone.USER_STORAGE_ROOT
        if (USER_ROOTS.any { isUnder(path, it) }) return Zone.USER
        if (Regex("^/data/(data|user/[0-9]+|user_de/[0-9]+)/[^/]+(/.*)?$").matches(path)) return Zone.APP_PRIVATE
        // Any other first-level directory ("/data", "/mnt", "/cache", "/odm"...).
        if (path.count { it == '/' } == 1) return Zone.ROOT_CHILD
        return Zone.OTHER
    }

    private val SEVERITY = listOf(
        Zone.ROOT, Zone.BOOT_CRITICAL, Zone.SYSTEM, Zone.ROOT_CHILD, Zone.USER_STORAGE_ROOT,
        Zone.APP_PRIVATE, Zone.USER, Zone.OTHER
    )

    private fun worst(zones: List<Zone>): Zone = zones.minByOrNull { SEVERITY.indexOf(it) } ?: Zone.OTHER

    /**
     * Zone of an argument, conservatively: Android's /sdcard is a symlink, so the kernel does not
     * resolve ".." the way the text suggests. Absolute paths with ".." are also judged by what follows
     * the last "..", and [resolver] (the real filesystem path, on device) catches symlinks into
     * the OS. Relative ".." is left to the lexical result so "rm -rf ../build" stays ordinary.
     */
    fun classifyArg(cwd: String, arg: String, resolver: ((String) -> String?)? = null): Target {
        val lexical = normalize(cwd, arg)
        val zones = mutableListOf(classify(lexical))
        if (arg.startsWith("/") && arg.split('/').contains("..")) {
            val tail = arg.split('/').let { parts -> parts.drop(parts.lastIndexOf("..") + 1) }
            // "/sdcard/x/.." has no tail: the lexical result already covers it.
            if (tail.any { it.isNotEmpty() && it != "." }) zones += classify(normalize("/", tail.joinToString("/")))
        }
        resolver?.invoke(lexical)?.let { real -> zones += classify(normalize("/", real)) }
        return Target(lexical, worst(zones))
    }

    private val MAIN_USER_FOLDER = Regex(
        "^/(sdcard|storage/emulated/[0-9]+|storage/self/primary|storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/" +
            "(DCIM|Pictures|Download|Documents|Movies|Music|WhatsApp|Telegram|Android)$"
    )

    fun isMainUserFolder(path: String): Boolean = MAIN_USER_FOLDER.matches(path)

    /**
     * Changes (not deletions) that break the boot: anything on "/" or a top-level folder, and
     * moving or recursively changing permissions/owners of the OS (`chmod -R 777 /system`).
     */
    fun isCatastrophicChange(zone: Zone, recursive: Boolean, command: String): Boolean = when (zone) {
        Zone.ROOT, Zone.ROOT_CHILD -> true
        Zone.SYSTEM, Zone.BOOT_CRITICAL -> recursive || command == "mv"
        else -> false
    }

    /** Wiping these makes the device unbootable or erases everything the user has. */
    fun isCatastrophic(zone: Zone, recursive: Boolean): Boolean = when (zone) {
        Zone.ROOT, Zone.ROOT_CHILD, Zone.SYSTEM, Zone.BOOT_CRITICAL -> true
        Zone.USER_STORAGE_ROOT -> recursive
        else -> false
    }

    /**
     * @param argv the command as the shell would run it, already glob-expanded, e.g.
     * `["rm", "-rf", "/sdcard/tmp"]` or `["busybox", "rm", "x"]`.
     */
    fun analyze(cwd: String, argv: List<String>, resolver: ((String) -> String?)? = null): Analysis {
        if (argv.isEmpty()) return Analysis("", Kind.DELETE, emptyList(), false, false)
        var args = argv
        var name = args.first().substringAfterLast('/')
        // Multi-call binaries: "busybox rm ...", "toybox rm ...".
        if ((name == "busybox" || name == "toybox") && args.size > 1) {
            args = args.drop(1)
            name = args.first().substringAfterLast('/')
        }
        val rest = args.drop(1)
        fun targets(paths: List<String>) = paths.map { classifyArg(cwd, it, resolver) }
        return when (name) {
            "rm" -> {
                val (flags, operands) = splitOptions(rest)
                val recursive = flags.any {
                    it == "--recursive" || (!it.startsWith("--") && (it.contains('r') || it.contains('R')))
                }
                Analysis(name, Kind.DELETE, targets(operands), recursive, operands.isNotEmpty())
            }
            "rmdir", "unlink" -> {
                val (_, operands) = splitOptions(rest)
                Analysis(name, Kind.DELETE, targets(operands), false, operands.isNotEmpty())
            }
            "find" -> {
                // Start points come before the first expression token.
                val starts = rest.takeWhile { !it.startsWith("-") && it != "(" && it != "!" }
                    .ifEmpty { listOf(".") }
                val deletes = rest.contains("-delete") || rest.windowed(2).any { (a, b) ->
                    (a == "-exec" || a == "-execdir" || a == "-ok") &&
                        b.substringAfterLast('/') in setOf("rm", "rmdir", "unlink", "shred")
                }
                Analysis(name, Kind.DELETE, targets(starts), true, deletes)
            }
            "dd" -> {
                val of = rest.firstOrNull { it.startsWith("of=") }?.removePrefix("of=")
                val target = of?.let { targets(listOf(it)) }.orEmpty()
                val toDevice = target.any { it.zone == Zone.BOOT_CRITICAL || it.path.startsWith("/dev/") }
                Analysis(name, Kind.BLOCK_DEVICE_WRITE, target, false, toDevice)
            }
            "mkfs", "mke2fs", "make_ext4fs", "mkfs.ext4", "mkfs.f2fs", "mkfs.vfat", "newfs_msdos",
            "wipefs", "blkdiscard", "flash_image", "sgdisk", "sfdisk", "fdisk", "parted" -> {
                val operands = splitOptions(rest).second
                val target = targets(operands.filter { it.startsWith("/") || it.contains("block") })
                // Formatting an image file in user storage is fine; only real devices matter.
                Analysis(name, Kind.BLOCK_DEVICE_WRITE, target, false, target.any { it.path.startsWith("/dev/") })
            }
            "mv" -> {
                // Both ends change: the source disappears and the destination is replaced.
                val (_, operands) = splitOptions(rest.filterNot { it == "-t" })
                val t = targets(operands)
                Analysis(name, Kind.MODIFY, t, false, operands.size >= 2)
            }
            "cp", "install", "rsync" -> {
                val (flags, operands) = splitOptions(rest)
                val targetDirFlag = rest.indexOf("-t").takeIf { it >= 0 && it + 1 < rest.size }?.let { rest[it + 1] }
                val dest = targetDirFlag ?: operands.lastOrNull()?.takeIf { operands.size >= 2 }
                val recursive = flags.any { it == "--recursive" || it == "-a" || (!it.startsWith("--") && (it.contains('r') || it.contains('R') || it.contains('a'))) }
                Analysis(name, Kind.MODIFY, dest?.let { targets(listOf(it)) }.orEmpty(), recursive, dest != null)
            }
            "chmod", "chown", "chgrp", "chcon", "setfattr", "setfacl" -> {
                val (flags, operands) = splitOptions(rest)
                val recursive = flags.any { it == "--recursive" || (!it.startsWith("--") && it.contains('R')) }
                // The first operand is the mode/owner/context unless --reference gives it.
                val files = if (flags.any { it.startsWith("--reference") }) operands else operands.drop(1)
                Analysis(name, Kind.MODIFY, targets(files), recursive, files.isNotEmpty())
            }
            "ln" -> {
                val (flags, operands) = splitOptions(rest)
                // Only replacing an existing name is a change (ln -sf new /system/bin/x).
                val replaces = flags.any { it == "--force" || (!it.startsWith("--") && it.contains('f')) }
                val link = operands.lastOrNull()?.takeIf { operands.size >= 2 }
                Analysis(name, Kind.MODIFY, link?.let { targets(listOf(it)) }.orEmpty(), false, replaces && link != null)
            }
            "truncate", "shred" -> {
                val operands = splitOptions(rest).second.let { ops ->
                    // "-s 0 file": the size value is not a file.
                    val sizeIdx = rest.indexOf("-s")
                    if (sizeIdx >= 0 && sizeIdx + 1 < rest.size) ops - rest[sizeIdx + 1] else ops
                }
                Analysis(name, Kind.MODIFY, targets(operands), false, operands.isNotEmpty())
            }
            "mount" -> {
                // Only making a mounted OS writable, or mounting over it, changes anything.
                val options = rest.windowed(2).filter { it[0] == "-o" }.joinToString(",") { it[1] } +
                    rest.filter { it.startsWith("-o") && it.length > 2 }.joinToString(",") { it.removePrefix("-o") }
                val optionSet = options.split(',').map { it.trim() }.toSet()
                val operands = splitOptions(rest.filterIndexed { i, _ -> i == 0 || rest[i - 1] != "-o" && rest[i - 1] != "-t" }).second
                val writable = "rw" in optionSet && "remount" in optionSet
                val mountsOver = "remount" !in optionSet && operands.size >= 2
                val point = operands.lastOrNull()
                Analysis(name, Kind.MODIFY, point?.let { targets(listOf(it)) }.orEmpty(), false, point != null && (writable || mountsOver))
            }
            "insmod" -> {
                val (flags, operands) = splitOptions(rest)
                val file = operands.firstOrNull()
                Analysis(
                    name, Kind.KERNEL_MODULE, file?.let { targets(listOf(it)) }.orEmpty(), false, file != null,
                    module = file?.let { normalize(cwd, it) }, forced = flags.any { it == "-f" || it == "--force" }
                )
            }
            "modprobe" -> {
                val (flags, operands) = splitOptions(rest)
                val module = operands.firstOrNull()
                val removing = flags.any { it == "-r" || it == "--remove" }
                Analysis(
                    if (removing) "rmmod" else name, Kind.KERNEL_MODULE, emptyList(), false, module != null,
                    module = module?.let { if (it.contains('/')) normalize(cwd, it) else it },
                    forced = flags.any { it == "-f" || it == "--force" || it == "--force-vermagic" || it == "--force-modversion" }
                )
            }
            "rmmod" -> {
                val (flags, operands) = splitOptions(rest)
                Analysis(name, Kind.KERNEL_MODULE, emptyList(), false, operands.isNotEmpty(),
                    module = operands.firstOrNull(), forced = flags.any { it == "-f" || it == "--force" })
            }
            else -> Analysis(name, Kind.DELETE, emptyList(), false, false)
        }
    }

    /** Options before "--"; everything after "--" (or not starting with "-") is an operand. */
    private fun splitOptions(args: List<String>): Pair<List<String>, List<String>> {
        val flags = mutableListOf<String>()
        val operands = mutableListOf<String>()
        var endOfOptions = false
        for (arg in args) {
            when {
                endOfOptions -> operands += arg
                arg == "--" -> endOfOptions = true
                arg.startsWith("-") && arg.length > 1 -> flags += arg
                else -> operands += arg
            }
        }
        return flags to operands
    }

    /**
     * @param privilege who runs it: without root only massive deletions of the user's own files
     * can do harm, so nothing else is ever asked or blocked.
     * @param deadMan Dead Man protection: never wipe the OS, boot chain or all user data.
     * @param dfroot DirtyFlag/DFRoot protection: never write partitions or OS/boot files at all.
     * @param deletedCount files the deletion would remove, when the app could count them.
     * @param module what the app found in the kernel module being loaded.
     */
    fun decide(
        analysis: Analysis,
        policy: Policy,
        deadMan: Boolean,
        dfroot: Boolean,
        privilege: Privilege = Privilege.ROOT,
        deletedCount: Int? = null,
        module: ModuleCheck? = null
    ): Decision {
        if (!analysis.destructive) return Decision(Verdict.ALLOW, Reason.NOT_DESTRUCTIVE, analysis)
        val massive = analysis.kind == Kind.DELETE &&
            (analysis.touchesStorageRootRecursively || (deletedCount ?: 0) >= MASS_DELETE_THRESHOLD)

        if (privilege == Privilege.USER) {
            // Android refuses any change to the OS, partitions or other apps for a normal app.
            if (analysis.kind != Kind.DELETE) return Decision(Verdict.ALLOW, Reason.UNPRIVILEGED, analysis)
            return when {
                policy == Policy.BLOCK -> Decision(Verdict.BLOCK, Reason.POLICY_BLOCK, analysis)
                policy == Policy.ALLOW -> Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
                massive -> Decision(Verdict.ASK, Reason.MASS_DELETE, analysis)
                else -> Decision(Verdict.ALLOW, Reason.UNPRIVILEGED, analysis)
            }
        }

        if (deadMan && analysis.catastrophicTargets.isNotEmpty()) {
            return Decision(Verdict.BLOCK, Reason.DEAD_MAN, analysis)
        }
        // "/" is the system partition itself on current Android (system-as-root).
        if (dfroot && analysis.targets.any { it.zone == Zone.BOOT_CRITICAL || it.zone == Zone.SYSTEM || it.zone == Zone.ROOT }) {
            return Decision(Verdict.BLOCK, Reason.DFROOT, analysis)
        }
        if (deadMan && analysis.kind == Kind.BLOCK_DEVICE_WRITE) {
            // Writing raw partitions / formatting is never an everyday action.
            return Decision(Verdict.BLOCK, Reason.DEAD_MAN, analysis)
        }
        if (analysis.kind == Kind.KERNEL_MODULE) {
            val loading = analysis.command != "rmmod"
            if (loading && module?.problem != null && (analysis.forced || deadMan || dfroot)) {
                return Decision(Verdict.BLOCK, Reason.KERNEL_MISMATCH, analysis)
            }
            if (loading && dfroot && module?.touchesPartitions == true) {
                return Decision(Verdict.BLOCK, Reason.DFROOT, analysis)
            }
            return when (policy) {
                Policy.BLOCK -> Decision(Verdict.BLOCK, Reason.POLICY_BLOCK, analysis)
                Policy.ALLOW -> Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
                // The kernel runs it with no limits at all: always show what it is first.
                else -> Decision(Verdict.ASK, Reason.KERNEL_MODULE, analysis)
            }
        }
        if (analysis.kind == Kind.MODIFY) {
            // Outside the OS (or with its protections off) changing files is ordinary work.
            return Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
        }
        return when (policy) {
            Policy.ALLOW -> Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
            Policy.BLOCK -> Decision(Verdict.BLOCK, Reason.POLICY_BLOCK, analysis)
            Policy.ASK -> Decision(Verdict.ASK, Reason.POLICY_ASK, analysis)
            Policy.DEFAULT ->
                if (massive) Decision(Verdict.ASK, Reason.MASS_DELETE, analysis)
                else Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
        }
    }
}
