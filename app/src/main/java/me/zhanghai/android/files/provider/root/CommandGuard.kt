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

    enum class Kind { DELETE, BLOCK_DEVICE_WRITE }

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
        NOT_DESTRUCTIVE
    }

    enum class Policy { ASK, ALLOW, BLOCK }

    data class Target(val path: String, val zone: Zone)

    data class Analysis(
        val command: String,
        val kind: Kind,
        val targets: List<Target>,
        val recursive: Boolean,
        val destructive: Boolean
    ) {
        val catastrophicTargets: List<Target>
            get() = targets.filter { isCatastrophic(it.zone, recursive) }
    }

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
    fun analyze(cwd: String, argv: List<String>): Analysis {
        if (argv.isEmpty()) return Analysis("", Kind.DELETE, emptyList(), false, false)
        var args = argv
        var name = args.first().substringAfterLast('/')
        // Multi-call binaries: "busybox rm ...", "toybox rm ...".
        if ((name == "busybox" || name == "toybox") && args.size > 1) {
            args = args.drop(1)
            name = args.first().substringAfterLast('/')
        }
        val rest = args.drop(1)
        fun targets(paths: List<String>) = paths.map { normalize(cwd, it).let { p -> Target(p, classify(p)) } }
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
     * @param deadMan Dead Man protection: never wipe the OS, boot chain or all user data.
     * @param dfroot DirtyFlag/DFRoot protection: never write partitions or OS/boot files at all.
     */
    fun decide(analysis: Analysis, policy: Policy, deadMan: Boolean, dfroot: Boolean): Decision {
        if (!analysis.destructive) return Decision(Verdict.ALLOW, Reason.NOT_DESTRUCTIVE, analysis)
        if (deadMan && analysis.catastrophicTargets.isNotEmpty()) {
            return Decision(Verdict.BLOCK, Reason.DEAD_MAN, analysis)
        }
        if (dfroot && analysis.targets.any { it.zone == Zone.BOOT_CRITICAL || it.zone == Zone.SYSTEM }) {
            return Decision(Verdict.BLOCK, Reason.DFROOT, analysis)
        }
        if (deadMan && analysis.kind == Kind.BLOCK_DEVICE_WRITE) {
            // Writing raw partitions / formatting is never an everyday action: always confirm.
            return Decision(Verdict.BLOCK, Reason.DEAD_MAN, analysis)
        }
        return when (policy) {
            Policy.ALLOW -> Decision(Verdict.ALLOW, Reason.POLICY_ALLOW, analysis)
            Policy.BLOCK -> Decision(Verdict.BLOCK, Reason.POLICY_BLOCK, analysis)
            Policy.ASK -> Decision(Verdict.ASK, Reason.POLICY_ASK, analysis)
        }
    }
}
