#!/usr/bin/env python3
"""Varre um rosbag v2.0 record-a-record e resume a estrutura (para reindex)."""
import struct
import sys
from collections import Counter
from pathlib import Path

MAGIC = b"#ROSBAG V2.0\n"


def read_record(buf, pos):
    (hlen,) = struct.unpack_from("<I", buf, pos); pos += 4
    hdr = buf[pos:pos + hlen]; pos += hlen
    (dlen,) = struct.unpack_from("<I", buf, pos); pos += 4
    data_pos = pos
    pos += dlen
    fields = {}
    p = 0
    while p < len(hdr):
        (flen,) = struct.unpack_from("<I", hdr, p); p += 4
        name, _, val = hdr[p:p + flen].partition(b"="); p += flen
        fields[name.decode()] = val
    return fields, data_pos, dlen, pos


def main():
    path = Path(sys.argv[1])
    buf = path.read_bytes()
    assert buf.startswith(MAGIC), "não é rosbag v2.0"
    pos = len(MAGIC)
    ops = Counter()
    compressions = Counter()
    n = 0
    first_header = None
    last_pos_before_eof = pos
    try:
        while pos < len(buf):
            last_pos_before_eof = pos
            fields, dpos, dlen, pos = read_record(buf, pos)
            op = fields.get("op", b"?")
            opv = op[0] if op else -1
            ops[opv] += 1
            if first_header is None:
                first_header = fields
            if opv == 5:  # chunk
                compressions[fields.get("compression", b"?")] += 1
            n += 1
            if n <= 6 or opv in (3,):
                pretty = {k: (v.hex() if len(v) <= 16 else f"<{len(v)}b>") for k, v in fields.items()}
                print(f"#{n} op={opv} @{last_pos_before_eof} dlen={dlen} {pretty}")
    except (struct.error, IndexError) as e:
        print(f"[parou em {last_pos_before_eof}/{len(buf)}: {e}]")
    print(f"\ntotal records: {n}")
    print(f"ops (op->count): {dict(ops)}")
    print(f"compressions: { {k.decode(): v for k,v in compressions.items()} }")
    print("op legend: 2=msg 3=bagheader 4=indexdata 5=chunk 6=chunkinfo 7=connection")


if __name__ == "__main__":
    main()
