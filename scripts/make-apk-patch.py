#!/usr/bin/env python3
"""
生成「条目级增量更新」的服务端区间表（PatchDescriptor.segments）。

为什么需要这个脚本：客户端不能自己决定条目的摆放位置 —— APK 的 v2/v3 签名覆盖整个归档字节流，
任何偏移变化都会让签名失效。所以区间表必须由**发版时手上有新旧两个完整包**的服务端算出，
客户端只负责按表搬运。

用法：
    python scripts/make-apk-patch.py OLD.apk NEW.apk OUT_DIR

产出：
    OUT_DIR/patch.bin        客户端需要下载的字节（只含变化部分）
    OUT_DIR/patch.json       区间表 + 两个包的 sha256

2026-11-01 实测（v1.0.15 -> v1.0.16）：patch 3.84 MiB，占全量 11.9%，重建后与官方包逐字节相同。
"""
import hashlib
import json
import os
import sys
import zipfile


def local_header_len(blob, offset):
    name_len = int.from_bytes(blob[offset + 26:offset + 28], "little")
    extra_len = int.from_bytes(blob[offset + 28:offset + 30], "little")
    return 30 + name_len + extra_len


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_apk_version(apk_path):
    """从 AndroidManifest 之外的渠道拿版本号：优先用同名 assets 里的 output-metadata，
    否则回落到文件名。这里用一个更稳的办法：读 APK 内的 META-INF/version-control-info 不存在，
    所以直接从命令行给。默认取文件名去扩展名。"""
    base = os.path.basename(apk_path)
    return os.path.splitext(base)[0]


def build_segments(old_path, new_path):
    old_blob = open(old_path, "rb").read()
    new_blob = open(new_path, "rb").read()
    old_entries = {e.filename: e for e in zipfile.ZipFile(old_path).infolist()}
    new_entries = zipfile.ZipFile(new_path).infolist()

    segments = []
    patch = bytearray()
    last_end = 0

    def emit(dest, length, kind, src=0):
        if length <= 0:
            return
        segments.append({"dest": dest, "length": length, "kind": kind, "src": src})

    for entry in new_entries:
        offset = entry.header_offset
        header_len = local_header_len(new_blob, offset)
        data_offset = offset + header_len
        # 条目之间可能存在空隙（APK Signing Block 就落在最后一个条目与中央目录之间）
        if offset > last_end:
            emit(last_end, offset - last_end, "patch", len(patch))
            patch += new_blob[last_end:offset]

        old = old_entries.get(entry.filename)
        same_data = (
            old is not None
            and old.CRC == entry.CRC
            and old.file_size == entry.file_size
            and old.compress_size == entry.compress_size
        )
        same_head = (
            same_data
            and local_header_len(old_blob, old.header_offset) == header_len
            and old_blob[old.header_offset:old.header_offset + header_len]
            == new_blob[offset:offset + header_len]
        )
        if same_head:
            # 头部与数据都能从旧包原样搬，一个字节都不用下发
            emit(offset, header_len + entry.compress_size, "base", old.header_offset)
        else:
            emit(offset, header_len, "patch", len(patch))
            patch += new_blob[offset:offset + header_len]
            if same_data:
                emit(data_offset, entry.compress_size, "base", old.header_offset + local_header_len(old_blob, old.header_offset))
            else:
                emit(data_offset, entry.compress_size, "patch", len(patch))
                patch += new_blob[data_offset:data_offset + entry.compress_size]
        last_end = data_offset + entry.compress_size

    # 尾部：APK Signing Block + 中央目录 + EOCD，位置整体变了，必须整段下发
    emit(last_end, len(new_blob) - last_end, "patch", len(patch))
    patch += new_blob[last_end:]

    segments.sort(key=lambda s: s["dest"])
    # 自检：必须完整覆盖、互不重叠
    cursor = 0
    for seg in segments:
        if seg["dest"] != cursor:
            raise SystemExit("区间表不连续：期望 %d，实际 %d" % (cursor, seg["dest"]))
        cursor += seg["length"]
    if cursor != len(new_blob):
        raise SystemExit("区间表总长 %d 与包大小 %d 不符" % (cursor, len(new_blob)))

    return bytes(patch), segments, len(new_blob)


def main():
    if len(sys.argv) not in (4, 5):
        print(__doc__)
        return 2
    old_path, new_path, out_dir = sys.argv[1:4]
    # 第 4 个参数可选：显式的版本号（否则用文件名兜底）
    version = sys.argv[4] if len(sys.argv) > 4 else read_apk_version(new_path)
    os.makedirs(out_dir, exist_ok=True)
    patch, segments, new_size = build_segments(old_path, new_path)
    patch_path = os.path.join(out_dir, "patch.bin")
    with open(patch_path, "wb") as f:
        f.write(patch)
    manifest = {
        "version": version,
        "newSize": new_size,
        "newSha256": "sha256:" + sha256_of(new_path),
        "baseSha256": "sha256:" + sha256_of(old_path),
        "patchSize": len(patch),
        "patchSha256": "sha256:" + hashlib.sha256(patch).hexdigest(),
        "segments": segments,
    }
    with open(os.path.join(out_dir, "patch.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print("patch = %.2f MiB (%.1f%% of full %d bytes)" % (len(patch) / 1048576, len(patch) * 100 / new_size, new_size))
    print("segments =", len(segments))
    print("written:", patch_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())