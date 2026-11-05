#!/usr/bin/env python3
"""Puts the patched classes on Suwayomi's released jar, leaving every other entry byte-identical.

The released jar has overlapping duplicate entries that zip libraries refuse to copy, so this appends the new
entries and repoints the central directory, which is what the JVM reads.

Usage: overlay-released-jar.py RELEASED.jar OUTPUT.jar AndroidCompat/build/classes/kotlin/main server/build/classes/kotlin/main
"""
import hashlib
import struct
import sys
import zipfile
import zlib
from pathlib import Path

released_path, output_path, *class_roots = map(Path, sys.argv[1:])
# The only classes with changed code; the others in their source files differ only in line numbers
PATCHED = {
    "eu/kanade/tachiyomi/network/NetworkHelper.class",
    "xyz/nulldev/androidcompat/webkit/KcefHelperKt.class",
    "xyz/nulldev/androidcompat/webkit/KcefWebViewProvider.class",
    "xyz/nulldev/androidcompat/webkit/KcefWebViewProvider$LifeSpanHandler.class",
    "xyz/nulldev/androidcompat/webkit/KcefWebViewProvider$ResourceRequestHandler.class",
    "xyz/nulldev/androidcompat/webkit/KcefWebViewProvider$init$1.class",
}
TIMESTAMP = (0x0000, 0x5D65)  # MS-DOS time and date for new entries, Nov 5 2026; only zip listings read it


def compiled(name):
    return next((root / name).read_bytes() for root in class_roots if (root / name).exists())


def local_entry(raw_name, flags, time, date, content, crc, compressed):
    header = struct.pack(
        "<4sHHHHHIIIHH", b"PK\x03\x04", 20, flags, 8, time, date, crc, len(compressed), len(content), len(raw_name), 0
    )
    return header + raw_name + compressed


def deflate(content):
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return compressor.compress(content) + compressor.flush()


data = released_path.read_bytes()
end_record = data.rfind(b"PK\x05\x06")
is_zip64 = data[end_record - 20 : end_record - 16] == b"PK\x06\x07"
if is_zip64:
    zip64_record = struct.unpack_from("<Q", data, end_record - 12)[0]
    directory_start = struct.unpack_from("<Q", data, zip64_record + 48)[0]
    directory_end = zip64_record
else:
    directory_start = struct.unpack_from("<I", data, end_record + 16)[0]
    directory_end = end_record

directory = bytearray(data[directory_start:directory_end])
trailer = bytearray(data[directory_end:])
new_entries = bytearray()
replaced = set()
offset = 0
while offset < len(directory):
    if directory[offset : offset + 4] != b"PK\x01\x02":
        sys.exit(f"Bad central directory record at {offset}")
    name_length, extra_length, comment_length = struct.unpack_from("<HHH", directory, offset + 28)
    raw_name = bytes(directory[offset + 46 : offset + 46 + name_length])
    name = raw_name.decode()
    if name in PATCHED:
        content = compiled(name)
        compressed = deflate(content)
        crc = zlib.crc32(content)
        flags, _, time, date = struct.unpack_from("<HHHH", directory, offset + 8)
        flags &= ~0x0008  # The new local header carries the sizes, so no data descriptor follows
        local_offset = directory_start + len(new_entries)
        new_entries += local_entry(raw_name, flags, time, date, content, crc, compressed)
        struct.pack_into("<HHHHIII", directory, offset + 8, flags, 8, time, date, crc, len(compressed), len(content))
        struct.pack_into("<I", directory, offset + 42, local_offset)
        replaced.add(name)
    offset += 46 + name_length + extra_length + comment_length

added = PATCHED - replaced
for name in sorted(added):
    raw_name = name.encode()
    content = compiled(name)
    compressed = deflate(content)
    crc = zlib.crc32(content)
    time, date = TIMESTAMP
    local_offset = directory_start + len(new_entries)
    new_entries += local_entry(raw_name, 0x0800, time, date, content, crc, compressed)
    directory += struct.pack(
        "<4sHHHHHHIIIHHHHHII", b"PK\x01\x02", 20, 20, 0x0800, 8, time, date, crc, len(compressed), len(content),
        len(raw_name), 0, 0, 0, 0, 0, local_offset,
    ) + raw_name

new_directory_start = directory_start + len(new_entries)
new_directory_end = new_directory_start + len(directory)
new_end_record = end_record - directory_end
if is_zip64:
    entries = struct.unpack_from("<Q", trailer, 32)[0] + len(added)
    struct.pack_into("<QQQQ", trailer, 24, entries, entries, len(directory), new_directory_start)
    struct.pack_into("<Q", trailer, end_record - 20 - directory_end + 8, new_directory_end)
disk_entries, total_entries, directory_size, directory_offset = struct.unpack_from("<HHII", trailer, new_end_record + 8)
struct.pack_into(
    "<HHII", trailer, new_end_record + 8,
    disk_entries if disk_entries == 0xFFFF else disk_entries + len(added),
    total_entries if total_entries == 0xFFFF else total_entries + len(added),
    directory_size if directory_size == 0xFFFFFFFF else len(directory),
    directory_offset if directory_offset == 0xFFFFFFFF else new_directory_start,
)
output = bytes(data[:directory_start]) + bytes(new_entries) + bytes(directory) + bytes(trailer)
output_path.write_bytes(output)

with zipfile.ZipFile(released_path) as before, zipfile.ZipFile(output_path) as after:
    old = {(i.filename, i.header_offset): i.CRC for i in before.infolist()}
    new = {(i.filename, i.header_offset): i.CRC for i in after.infolist()}
    gone = {key[0] for key in old.keys() - new.keys()}
    appeared = {key[0] for key in new.keys() - old.keys()}
    unchanged_differ = [key for key in old.keys() & new.keys() if old[key] != new[key]]
    if gone != replaced or appeared != PATCHED or unchanged_differ or len(new) != len(old) + len(added):
        sys.exit(f"Unexpected differences: {gone} {appeared} {unchanged_differ[:5]}")
    for name in PATCHED:
        if after.read(name) != compiled(name):
            sys.exit(f"{name} does not read back")
    print(f"{len(new)} unique entries, replaced {len(replaced)}, added {len(added)}")
if output[:directory_start] != data[:directory_start]:
    sys.exit("Existing entries changed")
print("sha256", hashlib.sha256(output).hexdigest())
