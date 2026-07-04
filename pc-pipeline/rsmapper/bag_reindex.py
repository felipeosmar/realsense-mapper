"""Reindexa um rosbag v2.0 que ficou sem índice.

Bags gravados pelo app Android (librealsense 2.54.2 via enableRecordToFile) não
recebem a seção de índice final quando a gravação é encerrada — o `index_pos` do
bag-header fica 0 e tanto o pyrealsense2 quanto o rs-convert recusam o arquivo
("Bag unindexed"). Como o AAR é binário, reindexamos no PC.

O que falta num bag assim é apenas a seção de índice ao final:
  - registros de conexão (op=7), um por tópico;
  - registros chunk_info (op=6), um por chunk, com posição/tempos/contagens;
e o campo `index_pos` do bag-header apontando para essa seção.

Tudo isso é reconstruível sem descomprimir dados: os chunks trazem as conexões
(op=7) no seu interior, e cada chunk é seguido por registros index_data (op=4)
com as contagens e timestamps por conexão. Não mexemos em nenhum byte existente
(offsets preservados) — só corrigimos 3 valores no header e anexamos o índice.
"""
from __future__ import annotations

import struct
from pathlib import Path

MAGIC = b"#ROSBAG V2.0\n"

# ops do formato rosbag v2.0
OP_MSG = 0x02
OP_BAG_HEADER = 0x03
OP_INDEX_DATA = 0x04
OP_CHUNK = 0x05
OP_CHUNK_INFO = 0x06
OP_CONNECTION = 0x07


def _read_record(buf: bytes, pos: int):
    """Lê um record: retorna (campos, header_span, data_pos, data_len, next_pos)."""
    (hlen,) = struct.unpack_from("<I", buf, pos)
    hstart = pos + 4
    hdr = buf[hstart:hstart + hlen]
    (dlen,) = struct.unpack_from("<I", buf, hstart + hlen)
    dpos = hstart + hlen + 4
    fields = {}
    p = 0
    while p < len(hdr):
        (flen,) = struct.unpack_from("<I", hdr, p)
        p += 4
        name, _, val = hdr[p:p + flen].partition(b"=")
        fields[name.decode()] = val
        p += flen
    return fields, (hstart, hlen), dpos, dlen, dpos + dlen


def _header(fields: dict[str, bytes]) -> bytes:
    out = bytearray()
    for name, val in fields.items():
        field = name.encode() + b"=" + val
        out += struct.pack("<I", len(field)) + field
    return bytes(out)


def _record(fields: dict[str, bytes], data: bytes) -> bytes:
    h = _header(fields)
    return struct.pack("<I", len(h)) + h + struct.pack("<I", len(data)) + data


def _op(v: int) -> bytes:
    return bytes([v])


def is_indexed(bag_path: Path) -> bool:
    with open(bag_path, "rb") as f:
        head = f.read(8192)
    if not head.startswith(MAGIC):
        raise ValueError("não é um rosbag v2.0")
    fields, *_ = _read_record(head, len(MAGIC))
    return struct.unpack("<Q", fields["index_pos"])[0] != 0


def reindex(src: Path, dst: Path) -> dict:
    """Escreve em `dst` uma cópia indexada de `src`. Retorna estatísticas."""
    src, dst = Path(src), Path(dst)
    buf = bytearray(Path(src).read_bytes())
    if not buf.startswith(MAGIC):
        raise ValueError("não é um rosbag v2.0")

    pos = len(MAGIC)
    # --- bag header (primeiro record) ---
    bh_fields, (bh_hstart, bh_hlen), _, _, pos = _read_record(buf, pos)

    connections: dict[int, bytes] = {}      # conn -> connection header (data do op=7)
    conn_topic: dict[int, bytes] = {}       # conn -> topic
    chunks: list[dict] = []                 # por chunk: pos, conns{conn:count}, t0, t1
    cur: dict | None = None

    while pos < len(buf):
        fields, (hstart, hlen), dpos, dlen, nxt = _read_record(buf, pos)
        op = fields["op"][0]
        if op == OP_CHUNK:
            chunk_pos = pos
            # conexões vivem dentro do chunk (compression=none)
            ip = dpos
            end = dpos + dlen
            while ip < end:
                ifields, _, idpos, idlen, ip = _read_record(buf, ip)
                if ifields["op"][0] == OP_CONNECTION:
                    c = struct.unpack("<I", ifields["conn"])[0]
                    connections[c] = buf[idpos:idpos + idlen]
                    conn_topic[c] = ifields.get("topic", b"")
            cur = {"pos": chunk_pos, "conns": {}, "t0": None, "t1": None}
            chunks.append(cur)
        elif op == OP_INDEX_DATA and cur is not None:
            conn = struct.unpack("<I", fields["conn"])[0]
            count = struct.unpack("<I", fields["count"])[0]
            cur["conns"][conn] = cur["conns"].get(conn, 0) + count
            # data = count × (time u64, offset u32)
            for i in range(count):
                (t,) = struct.unpack_from("<Q", buf, dpos + i * 12)
                cur["t0"] = t if cur["t0"] is None else min(cur["t0"], t)
                cur["t1"] = t if cur["t1"] is None else max(cur["t1"], t)
        pos = nxt

    # --- monta a seção de índice ---
    index = bytearray()
    for conn in sorted(connections):
        index += _record(
            {"conn": struct.pack("<I", conn), "topic": conn_topic[conn], "op": _op(OP_CONNECTION)},
            connections[conn],
        )
    for ch in chunks:
        data = b"".join(
            struct.pack("<II", conn, cnt) for conn, cnt in ch["conns"].items()
        )
        index += _record(
            {
                "ver": struct.pack("<I", 1),
                "chunk_pos": struct.pack("<Q", ch["pos"]),
                "start_time": struct.pack("<Q", ch["t0"] or 0),
                "end_time": struct.pack("<Q", ch["t1"] or 0),
                "count": struct.pack("<I", len(ch["conns"])),
                "op": _op(OP_CHUNK_INFO),
            },
            data,
        )

    index_pos = len(buf)  # a seção de índice começa no fim dos dados atuais

    # --- corrige os 3 valores do bag-header no lugar (tamanhos idênticos) ---
    def patch(name: str, value: bytes):
        hdr = buf[bh_hstart:bh_hstart + bh_hlen]
        p = 0
        while p < len(hdr):
            (flen,) = struct.unpack_from("<I", hdr, p)
            p += 4
            fname, _, _ = hdr[p:p + flen].partition(b"=")
            if fname.decode() == name:
                voff = bh_hstart + p + len(fname) + 1
                buf[voff:voff + len(value)] = value
                return
            p += flen
        raise KeyError(name)

    patch("index_pos", struct.pack("<Q", index_pos))
    patch("conn_count", struct.pack("<I", len(connections)))
    patch("chunk_count", struct.pack("<I", len(chunks)))

    Path(dst).write_bytes(bytes(buf) + bytes(index))
    return {
        "connections": len(connections),
        "chunks": len(chunks),
        "index_pos": index_pos,
        "messages": sum(sum(c["conns"].values()) for c in chunks),
    }
