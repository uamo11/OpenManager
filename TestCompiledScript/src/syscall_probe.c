/*
 * Copyright (c) 2026 OpenManager
 * Syscall & Root Diagnostic Probe (C Implementation)
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "syscall_probe.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/sysinfo.h>
#include <sys/stat.h>
#include <time.h>
#include <fcntl.h>
#include <errno.h>

#define BENCHMARK_ITERATIONS 50000

int run_syscall_probe(SyscallProbeResult* out) {
    if (!out) return -1;
    memset(out, 0, sizeof(SyscallProbeResult));

    // 1. Direct Low-Level Linux Syscalls for Process Credentials
#ifdef SYS_getpid
    out->pid = (int)syscall(SYS_getpid);
#else
    out->pid = (int)getpid();
#endif

#ifdef SYS_getppid
    out->ppid = (int)syscall(SYS_getppid);
#else
    out->ppid = (int)getppid();
#endif

#ifdef SYS_getuid
    out->uid = (int)syscall(SYS_getuid);
#else
    out->uid = (int)getuid();
#endif

#ifdef SYS_geteuid
    out->euid = (int)syscall(SYS_geteuid);
#else
    out->euid = (int)geteuid();
#endif

#ifdef SYS_getgid
    out->gid = (int)syscall(SYS_getgid);
#else
    out->gid = (int)getgid();
#endif

#ifdef SYS_getegid
    out->egid = (int)syscall(SYS_getegid);
#else
    out->egid = (int)getegid();
#endif

    out->is_root = (out->uid == 0 || out->euid == 0) ? 1 : 0;

    // 2. Syscall: uname (Kernel version & system architecture)
    struct utsname u;
    memset(&u, 0, sizeof(u));
#ifdef SYS_uname
    int uname_res = (int)syscall(SYS_uname, &u);
#else
    int uname_res = uname(&u);
#endif
    if (uname_res == 0) {
        strncpy(out->sysname, u.sysname, sizeof(out->sysname) - 1);
        strncpy(out->nodename, u.nodename, sizeof(out->nodename) - 1);
        strncpy(out->release, u.release, sizeof(out->release) - 1);
        strncpy(out->version, u.version, sizeof(out->version) - 1);
        strncpy(out->machine, u.machine, sizeof(out->machine) - 1);
    }

    // 3. Syscall: sysinfo (Memory & System Resources)
    struct sysinfo s;
    memset(&s, 0, sizeof(s));
#ifdef SYS_sysinfo
    int s_res = (int)syscall(SYS_sysinfo, &s);
#else
    int s_res = sysinfo(&s);
#endif
    if (s_res == 0) {
        unsigned long unit = s.mem_unit ? s.mem_unit : 1;
        out->total_ram_mb = (unsigned long)((s.totalram * unit) / (1024 * 1024));
        out->free_ram_mb = (unsigned long)((s.freeram * unit) / (1024 * 1024));
        out->procs_count = s.procs;
        out->uptime_sec = s.uptime;
    }

    // 4. Read /proc/version
    int fd_ver = open("/proc/version", O_RDONLY);
    if (fd_ver >= 0) {
        ssize_t bytes = read(fd_ver, out->proc_version, sizeof(out->proc_version) - 1);
        if (bytes > 0) {
            out->proc_version[bytes] = '\0';
            // Trim trailing newline
            char* nl = strchr(out->proc_version, '\n');
            if (nl) *nl = '\0';
        }
        close(fd_ver);
    }

    // 5. Check SELinux Enforce
    out->selinux_enforce = -1;
    int fd_se = open("/sys/fs/selinux/enforce", O_RDONLY);
    if (fd_se >= 0) {
        char buf[8] = {0};
        if (read(fd_se, buf, sizeof(buf) - 1) > 0) {
            if (buf[0] == '1') out->selinux_enforce = 1;
            else if (buf[0] == '0') out->selinux_enforce = 0;
        }
        close(fd_se);
    }

    // 6. Superuser binary discovery
    static const char* su_paths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
        "/system/bin/ksud",
        "/system/bin/magisk"
    };
    out->su_binary_detected = 0;
    for (size_t i = 0; i < sizeof(su_paths) / sizeof(su_paths[0]); i++) {
        struct stat st;
        if (stat(su_paths[i], &st) == 0) {
            out->su_binary_detected = 1;
            strncpy(out->su_binary_path, su_paths[i], sizeof(out->su_binary_path) - 1);
            break;
        }
    }

    // 7. Micro-Benchmark: Direct Syscall Latency
    struct timespec t_start, t_end;
    clock_gettime(CLOCK_MONOTONIC, &t_start);
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
#ifdef SYS_getpid
        (void)syscall(SYS_getpid);
#else
        (void)getpid();
#endif
    }
    clock_gettime(CLOCK_MONOTONIC, &t_end);
    double elapsed_ns = (double)(t_end.tv_sec - t_start.tv_sec) * 1e9 +
                        (double)(t_end.tv_nsec - t_start.tv_nsec);
    out->syscall_bench_ns = elapsed_ns / (double)BENCHMARK_ITERATIONS;

    return 0;
}

void print_syscall_probe_result(const SyscallProbeResult* res) {
    if (!res) return;

    printf("\n=======================================================\n");
    printf("   OpenManager Low-Level Syscall & Kernel Diagnostic   \n");
    printf("=======================================================\n");
    printf(" [Process Credentials (via direct syscall)]\n");
    printf("   - PID  : %d | PPID: %d\n", res->pid, res->ppid);
    printf("   - UID  : %d | EUID: %d %s\n", res->uid, res->euid, res->is_root ? "[ROOT ACTIVE]" : "[NON-ROOT]");
    printf("   - GID  : %d | EGID: %d\n", res->gid, res->egid);
    printf("\n [Linux Kernel / Architecture]\n");
    printf("   - OS   : %s (%s)\n", res->sysname, res->machine);
    printf("   - Kernel Release: %s\n", res->release);
    printf("   - Kernel Version: %s\n", res->version);
    if (res->proc_version[0]) {
        printf("   - /proc/version : %s\n", res->proc_version);
    }
    printf("\n [Hardware & Memory via sysinfo]\n");
    printf("   - Total RAM : %lu MB\n", res->total_ram_mb);
    printf("   - Free RAM  : %lu MB\n", res->free_ram_mb);
    printf("   - Live Procs: %u\n", res->procs_count);
    printf("   - Uptime    : %ld sec (%.1f hours)\n", res->uptime_sec, (double)res->uptime_sec / 3600.0);
    printf("\n [Security & Privileges]\n");
    printf("   - Root Elevation: %s\n", res->is_root ? "\033[1;32mYES (UID 0)\033[0m" : "\033[1;33mNO (Standard user)\033[0m");
    const char* se_str = (res->selinux_enforce == 1) ? "\033[1;32mEnforcing (1)\033[0m" :
                         (res->selinux_enforce == 0) ? "\033[1;33mPermissive (0)\033[0m" : "\033[1;30mDisabled / Unknown\033[0m";
    printf("   - SELinux Mode  : %s\n", se_str);
    if (res->su_binary_detected) {
        printf("   - SU Binary     : \033[1;32mFound at %s\033[0m\n", res->su_binary_path);
    } else {
        printf("   - SU Binary     : Not detected in standard paths\n");
    }
    printf("\n [Performance Benchmark]\n");
    printf("   - Direct Syscall Latency : \033[1;36m%.2f ns/call\033[0m (%d iterations)\n",
           res->syscall_bench_ns, BENCHMARK_ITERATIONS);
    printf("=======================================================\n\n");
}
