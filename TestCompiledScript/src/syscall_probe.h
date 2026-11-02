/*
 * Copyright (c) 2026 OpenManager
 * Syscall & Root Diagnostic Probe (C Module)
 */

#ifndef SYSCALL_PROBE_H
#define SYSCALL_PROBE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int pid;
    int ppid;
    int uid;
    int euid;
    int gid;
    int egid;
    int is_root;

    char sysname[65];
    char nodename[65];
    char release[65];
    char version[65];
    char machine[65];

    unsigned long total_ram_mb;
    unsigned long free_ram_mb;
    unsigned short procs_count;
    long uptime_sec;

    int selinux_enforce; // -1: unknown/disabled, 0: permissive, 1: enforcing
    char proc_version[256];

    int su_binary_detected;
    char su_binary_path[256];

    double syscall_bench_ns; // average nanoseconds per direct syscall
} SyscallProbeResult;

/**
 * Executes low-level Linux syscalls, gathers system diagnostic data,
 * checks root privileges, and runs micro-benchmarks.
 */
int run_syscall_probe(SyscallProbeResult* out);

/**
 * Prints the probe result cleanly formatted to standard output.
 */
void print_syscall_probe_result(const SyscallProbeResult* res);

#ifdef __cplusplus
}
#endif

#endif // SYSCALL_PROBE_H
