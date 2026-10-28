#!/usr/bin/env python3
"""Compare texec's ELF page-tail initialization with the native Linux loader."""
import os
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix='trough-loader-pages-') as temporary:
        work = Path(temporary)
        cc = os.environ.get('CC', 'gcc')
        page = os.sysconf('SC_PAGESIZE')
        loader = work/'texec'
        subprocess.run([cc, '-O2', '-static', '-nostdlib', '-ffreestanding', '-fno-builtin',
                        '-fno-stack-protector', '-Wl,-Ttext-segment=0x6f00000000',
                        '-o', loader, ROOT/'native/texec/texec.c'], check=True)
        script = work/'fixture.ld'
        script.write_text('''ENTRY(_start)
PHDRS { text PT_LOAD FLAGS(5); data PT_LOAD FLAGS(6); }
SECTIONS {
  . = 0x400000; .text : { *(.text*) } :text
  . = 0x420000; .data : { *(.data*) } :data
  .bss : { *(.bss*) } :data
  /DISCARD/ : { *(.note*) *(.comment*) }
}
''')
        for bss_size in (17, 8193):
            source = work/'fixture.S'
            # Read the declared BSS and the final page tail, as ld.so's initial allocator
            # does. The native kernel is the reference; non-load file bytes are poison.
            source.write_text(''' .global _start
.text
_start:
  adrp x1, zero_start
  add x1, x1, :lo12:zero_start
  adrp x2, zero_end
  add x2, x2, :lo12:zero_end
  mov x4, #'''+str(page-1)+'''
  add x2, x2, x4
  bic x2, x2, x4
1:
  cmp x1, x2
  b.hs 2f
  ldrb w3, [x1], #1
  cbnz w3, 3f
  b 1b
2:
  mov x0, #0
  b 4f
3:
  mov x0, #23
4:
  mov x8, #93
  svc #0
.data
  .quad 0x12345678
.bss
zero_start:
  .skip '''+str(bss_size)+'''
zero_end:
''')
            target = work/f'fixture-{bss_size}'
            subprocess.run([cc, '-nostdlib', '-static', '-Wl,--build-id=none', '-Wl,-T,'+str(script),
                            '-o', target, source], check=True)
            data = bytearray(target.read_bytes())
            phoff, = struct.unpack_from('<Q', data, 32)
            phsize, phnum = struct.unpack_from('<HH', data, 54)
            for i in range(phnum):
                kind, flags, offset, va, _, filesz, memsz, _ = struct.unpack_from('<II6Q', data, phoff+i*phsize)
                if kind == 1 and flags & 2:
                    assert filesz < memsz and (va+filesz) % page
                    start = offset+filesz
                    end = (start+page-1) & -page
                    if len(data) < end:
                        data.extend(bytes(end-len(data)))
                    data[start:end] = b'\xa5'*(end-start)
            target.write_bytes(data)
            native = subprocess.run([target], check=False).returncode
            loaded = subprocess.run([loader, target], check=False).returncode
            assert native == 0, ('native reference failed', bss_size, native)
            assert loaded == native, ('texec exposes file bytes after BSS', bss_size, loaded)
            print(f'ok kernel/texec zero BSS and page tail with {bss_size} BSS bytes', flush=True)


if __name__ == '__main__':
    main()
