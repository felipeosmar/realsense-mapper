#!/usr/bin/env python3
"""Detalha conexões dentro dos chunks e os records finais de um rosbag v2.0."""
import struct, sys
from pathlib import Path
MAGIC = b"#ROSBAG V2.0\n"

def rec(buf, pos):
    (hlen,) = struct.unpack_from("<I", buf, pos); pos += 4
    hdr = buf[pos:pos+hlen]; pos += hlen
    (dlen,) = struct.unpack_from("<I", buf, pos); pos += 4
    dpos = pos; pos += dlen
    f = {}; p = 0
    while p < len(hdr):
        (fl,) = struct.unpack_from("<I", hdr, p); p += 4
        n, _, v = hdr[p:p+fl].partition(b"="); p += fl
        f[n.decode()] = v
    return f, dpos, dlen, pos

def parse_inner(buf, start, end):
    """Records dentro de um chunk (op=7 conexões, op=2 msgs)."""
    pos = start; conns = {}
    while pos < end:
        f, dpos, dlen, pos = rec(buf, pos)
        op = f.get("op", b"\x00")[0]
        if op == 7:
            conn = struct.unpack("<I", f["conn"])[0]
            conns[conn] = (f.get("topic", b"").decode(), buf[dpos:dpos+dlen])
    return conns

def main():
    buf = Path(sys.argv[1]).read_bytes()
    pos = len(MAGIC)
    all_conns = {}
    chunk_no = 0
    tail_ops = []
    while pos < len(buf):
        rec_start = pos
        f, dpos, dlen, pos = rec(buf, pos)
        op = f.get("op", b"\x00")[0]
        if op == 5:
            chunk_no += 1
            if chunk_no <= 2:
                c = parse_inner(buf, dpos, dpos+dlen)
                print(f"chunk#{chunk_no}@{rec_start}: conexões dentro = {[(k,v[0],len(v[1])) for k,v in c.items()]}")
            all_conns.update(parse_inner(buf, dpos, dpos+dlen))
        elif op == 2:
            tail_ops.append((rec_start, struct.unpack('<I', f['conn'])[0] if 'conn' in f else '?'))
    print(f"\nconexões únicas encontradas: {sorted(all_conns.keys())}")
    for k in sorted(all_conns):
        print(f"  conn {k}: topic={all_conns[k][0]!r} header={len(all_conns[k][1])}b")
    print(f"\nrecords op=2 no nível-topo (soltos): {len(tail_ops)}  primeiros: {tail_ops[:5]}")

if __name__ == "__main__":
    main()
