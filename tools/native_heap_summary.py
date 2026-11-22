#!/usr/bin/env python3
"""Summarize an Android native heap dump (`am dumpheap -n`) by owning library.

Usage: native_heap_summary.py <dump.txt> [top_n]

Each allocation is attributed to the first backtrace frame that lives outside the
allocator itself (libc, malloc debug, libc++), which is normally the library that
asked for the memory (libhwui, libandroid_runtime, libicuuc, our own JNI libs...).
"""

import bisect
import collections
import sys

ALLOCATOR_LIBS = (
    "libc.so",
    "libc_malloc_debug.so",
    "libc++.so",
    "libc++_shared.so",
    "libutils.so",
)


def parse(path):
    allocs = []
    maps = []
    in_maps = False
    with open(path, "r", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line == "MAPS":
                in_maps = True
                continue
            if line == "END":
                break
            if in_maps:
                parts = line.split()
                if not parts or "-" not in parts[0]:
                    continue
                start, end = parts[0].split("-")
                name = parts[5] if len(parts) >= 6 else "[anon]"
                offset = int(parts[2], 16) if len(parts) >= 3 else 0
                maps.append((int(start, 16), int(end, 16), name, offset))
                continue
            if not line.startswith("z "):
                continue
            tokens = line.split()
            try:
                size = int(tokens[tokens.index("sz") + 1])
                num = int(tokens[tokens.index("num") + 1])
            except (ValueError, IndexError):
                continue
            frames = []
            if "bt" in tokens:
                frames = [int(t, 16) for t in tokens[tokens.index("bt") + 1:]]
            allocs.append((size * num, num, frames))
    maps.sort()
    return allocs, maps


def lookup(maps, starts, pc):
    i = bisect.bisect_right(starts, pc) - 1
    if i >= 0 and maps[i][0] <= pc < maps[i][1]:
        return maps[i]
    return None


def short(name):
    return name.rsplit("/", 1)[-1]


def main():
    path = sys.argv[1]
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    allocs, maps = parse(path)
    starts = [m[0] for m in maps]
    by_lib = collections.Counter()
    by_site = collections.Counter()
    total = 0
    for size, _num, frames in allocs:
        total += size
        owner = "[no backtrace]"
        chain = []
        for pc in frames:
            m = lookup(maps, starts, pc)
            lib = short(m[2]) if m else "[unknown]"
            if lib in ALLOCATOR_LIBS and not chain:
                continue
            if chain and chain[-1] == lib:
                continue
            chain.append(lib)
            if len(chain) >= 4:
                break
        if chain:
            owner = chain[0]
        by_lib[owner] += size
        by_site[" <- ".join(chain) if chain else owner] += size

    mib = 1024.0 * 1024.0
    print(f"Native heap dump: {path}")
    print(f"Total live allocations: {total / mib:.2f} MiB in {len(allocs)} records")
    print("\n--- By owning library ---")
    for lib, size in by_lib.most_common(top_n):
        print(f"{size / mib:8.2f} MiB  {100.0 * size / max(total, 1):5.1f}%  {lib}")
    print("\n--- By call chain (distinct libs, 4 deep) ---")
    for site, size in by_site.most_common(top_n):
        print(f"{size / mib:8.2f} MiB  {site}")


if __name__ == "__main__":
    main()
