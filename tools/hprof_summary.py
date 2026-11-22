#!/usr/bin/env python3
"""Minimal Android hprof analyzer: app-heap class histogram and Bitmap inventory.

Usage: hprof_summary.py <dump.hprof> [top_n]

Only the "app" heap is counted (zygote and boot image objects are shared with every
process and are not something the app can reduce).
"""

import collections
import struct
import sys

BASIC_SIZES = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
PRIM_NAMES = {4: "boolean[]", 5: "char[]", 6: "float[]", 7: "double[]", 8: "byte[]",
              9: "short[]", 10: "int[]", 11: "long[]"}


def main():
    path = sys.argv[1]
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    data = open(path, "rb").read()
    nul = data.index(b"\0")
    id_size = struct.unpack(">I", data[nul + 1:nul + 5])[0]
    sizes = dict(BASIC_SIZES)
    sizes[2] = id_size
    id_fmt = ">I" if id_size == 4 else ">Q"

    def rid(off):
        return struct.unpack_from(id_fmt, data, off)[0]

    strings = {}
    class_names = {}
    class_info = {}  # class id -> (super id, [(name id, type)], instance size)
    hist_count = collections.Counter()
    hist_bytes = collections.Counter()
    instances = []  # (class id, offset of field bytes, length, object id)
    obj_arrays = []  # (class id, offset of elements, count)
    heap = "app"

    pos = nul + 1 + 4 + 8
    end = len(data)
    while pos < end:
        tag = data[pos]
        length = struct.unpack_from(">I", data, pos + 5)[0]
        body = pos + 9
        if tag == 0x01:
            sid = rid(body)
            strings[sid] = data[body + id_size:body + length].decode("utf-8", "replace")
        elif tag == 0x02:
            cid = rid(body + 4)
            nid = rid(body + 8 + id_size)
            class_names[cid] = nid
        elif tag in (0x0C, 0x1C):
            p = body
            seg_end = body + length
            while p < seg_end:
                sub = data[p]
                p += 1
                if sub == 0xFF:
                    p += id_size
                elif sub in (0x01,):
                    p += 2 * id_size
                elif sub in (0x02, 0x03, 0x08):
                    p += id_size + 8
                elif sub in (0x04, 0x06):
                    p += id_size + 4
                elif sub in (0x05, 0x07, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x90):
                    p += id_size
                elif sub == 0x8E:
                    p += id_size + 8
                elif sub == 0xFE:
                    heap_id = struct.unpack_from(">I", data, p)[0]
                    heap = strings.get(rid(p + 4), str(heap_id))
                    p += 4 + id_size
                elif sub == 0x20:
                    cid = rid(p)
                    super_id = rid(p + id_size + 4)
                    q = p + id_size + 4 + 6 * id_size
                    inst_size = struct.unpack_from(">I", data, q)[0]
                    q += 4
                    n = struct.unpack_from(">H", data, q)[0]
                    q += 2
                    for _ in range(n):
                        t = data[q + 2]
                        q += 3 + sizes[t]
                    n = struct.unpack_from(">H", data, q)[0]
                    q += 2
                    for _ in range(n):
                        t = data[q + id_size]
                        q += id_size + 1 + sizes[t]
                    n = struct.unpack_from(">H", data, q)[0]
                    q += 2
                    fields = []
                    for _ in range(n):
                        fields.append((rid(q), data[q + id_size]))
                        q += id_size + 1
                    class_info[cid] = (super_id, fields, inst_size)
                    p = q
                elif sub == 0x21:
                    cid = rid(p + id_size + 4)
                    n = struct.unpack_from(">I", data, p + 2 * id_size + 4)[0]
                    if heap == "app":
                        hist_count[cid] += 1
                        hist_bytes[cid] += n
                        instances.append((cid, p + 2 * id_size + 8, n, rid(p)))
                    p += 2 * id_size + 8 + n
                elif sub == 0x22:
                    n = struct.unpack_from(">I", data, p + id_size + 4)[0]
                    cid = rid(p + id_size + 8)
                    if heap == "app":
                        hist_count[cid] += 1
                        hist_bytes[cid] += n * id_size
                        obj_arrays.append((cid, p + 2 * id_size + 8, n))
                    p += 2 * id_size + 8 + n * id_size
                elif sub in (0x23, 0xC3):
                    n = struct.unpack_from(">I", data, p + id_size + 4)[0]
                    t = data[p + id_size + 8]
                    if heap == "app":
                        key = PRIM_NAMES.get(t, "prim[]")
                        hist_count[key] += 1
                        hist_bytes[key] += n * sizes[t]
                    p += id_size + 9 + (n * sizes[t] if sub == 0x23 else 0)
                else:
                    raise SystemExit(f"Unknown heap sub-tag 0x{sub:02x} at {p - 1}")
        pos = body + length

    def name_of(key):
        if isinstance(key, str):
            return key
        return strings.get(class_names.get(key), f"class@{key:x}")

    total = sum(hist_bytes.values())
    print(f"App heap (shallow): {total / 1048576:.2f} MiB, {sum(hist_count.values())} objects")
    print("\n--- Top classes by shallow size ---")
    for key, size in hist_bytes.most_common(top_n):
        print(f"{size / 1024:10.1f} KiB {hist_count[key]:8d}  {name_of(key)}")

    # Who references the classes passed as extra arguments?
    targets_by_name = sys.argv[3:]
    layouts = {}

    def layout_of(c):
        if c not in layouts:
            fl = []
            k = c
            while k in class_info:
                fl.extend(class_info[k][1])
                k = class_info[k][0]
            layouts[c] = fl
        return layouts[c]

    for target_name in targets_by_name:
        tids = {oid for cid, _o, _n, oid in instances if name_of(cid) == target_name}
        refs = collections.Counter()
        for cid, off, _n, _oid in instances:
            q = off
            for nid, t in layout_of(cid):
                if t == 2 and rid(q) in tids:
                    refs[f"{name_of(cid)}.{strings.get(nid, '?')}"] += 1
                q += sizes[t]
        for cid, off, n in obj_arrays:
            for i in range(n):
                if rid(off + i * id_size) in tids:
                    refs[f"{name_of(cid)}[]"] += 1
        print(f"\n--- Referrers of {target_name} ({len(tids)} objects) ---")
        for k, v in refs.most_common(top_n):
            print(f"{v:8d}  {k}")

    # Bitmap inventory (pixels live in the native heap on API 26+).
    bitmap_cid = None
    for cid in class_info:
        if name_of(cid) == "android.graphics.Bitmap":
            bitmap_cid = cid
    if bitmap_cid is None:
        return
    layout = []
    cid = bitmap_cid
    while cid in class_info:
        layout.extend(class_info[cid][1])
        cid = class_info[cid][0]
    print("\n--- Bitmaps on app heap (width x height => approx ARGB bytes) ---")
    bitmaps = []
    for cid, off, _n, _oid in instances:
        if cid != bitmap_cid:
            continue
        vals = {}
        q = off
        for nid, t in layout:
            fname = strings.get(nid, "?")
            sz = sizes[t]
            if t == 10:
                vals[fname] = struct.unpack_from(">i", data, q)[0]
            q += sz
        w, h = vals.get("mWidth", 0), vals.get("mHeight", 0)
        bitmaps.append((w * h * 4, w, h))
    bitmaps.sort(reverse=True)
    print(f"{len(bitmaps)} bitmaps, ~{sum(b[0] for b in bitmaps) / 1048576:.2f} MiB of pixels")
    for size, w, h in bitmaps[:top_n]:
        print(f"{size / 1024:10.1f} KiB  {w}x{h}")


if __name__ == "__main__":
    main()
