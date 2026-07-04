#!/usr/bin/env python3
"""Diz se um .bag do RealSense (rosbag v2.0) está indexado — lendo só o cabeçalho.

O writer grava um bag-header logo após o magic com o campo `index_pos`. Enquanto
grava ele fica 0; ao encerrar limpo o writer volta e escreve a posição real do
índice. Então: index_pos == 0  => sem índice (ilegível no pipeline);
              index_pos  > 0  => indexado (ok).

Uso: python bag_indexed.py cabecalho.bin   (ou o .bag inteiro)
Basta passar os primeiros ~8 KB do arquivo.
"""
import struct
import sys
from pathlib import Path

MAGIC = b"#ROSBAG V2.0\n"


def read_index_pos(header_bytes: bytes) -> int:
    if not header_bytes.startswith(MAGIC):
        raise ValueError("não é um rosbag v2.0 (magic ausente)")
    pos = len(MAGIC)
    # primeiro record = bag header (op=0x03). Formato do record:
    #   <4 bytes header_len><header fields><4 bytes data_len><data>
    header_len = struct.unpack_from("<I", header_bytes, pos)[0]
    pos += 4
    fields_end = pos + header_len
    index_pos = None
    while pos < fields_end:
        field_len = struct.unpack_from("<I", header_bytes, pos)[0]
        pos += 4
        field = header_bytes[pos:pos + field_len]
        pos += field_len
        name, _, value = field.partition(b"=")
        if name == b"index_pos":
            index_pos = struct.unpack("<Q", value)[0]
    if index_pos is None:
        raise ValueError("campo index_pos não encontrado no bag header")
    return index_pos


def main() -> None:
    data = Path(sys.argv[1]).read_bytes()
    ip = read_index_pos(data[:8192])
    if ip > 0:
        print(f"INDEXADO (index_pos={ip}) — ok para o pipeline")
        sys.exit(0)
    print("SEM ÍNDICE (index_pos=0) — gravação não foi finalizada")
    sys.exit(1)


if __name__ == "__main__":
    main()
