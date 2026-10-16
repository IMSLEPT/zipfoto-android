#!/usr/bin/env python3
"""Verifica indipendente di uno ZIP prodotto da ZipSelfTest: CRC, dimensioni, nomi, zip64."""
import sys
import zipfile

zip_path, manifest_path = sys.argv[1], sys.argv[2]

expected = {}
with open(manifest_path, encoding="utf-8") as f:
    for line in f:
        name, crc, size = line.rstrip("\n").split("\t")
        expected[name] = (int(crc), int(size))

with zipfile.ZipFile(zip_path) as z:
    infos = z.infolist()
    assert len(infos) == len(expected), f"numero file diverso: {len(infos)} vs {len(expected)}"
    max_offset = 0
    for info in infos:
        crc, size = expected[info.filename]
        assert info.CRC == crc, f"CRC diverso (directory) per {info.filename}"
        assert info.file_size == size, f"dimensione diversa per {info.filename}"
        max_offset = max(max_offset, info.header_offset)
    bad = z.testzip()  # rilegge e decomprime tutto controllando i CRC
    assert bad is None, f"testzip ha trovato un errore in {bad}"

print(f"OK  file={len(infos)}  offset_max={max_offset} ({max_offset / 2**30:.2f} GiB)  zip64={'si' if max_offset >= 2**32 else 'no'}")
