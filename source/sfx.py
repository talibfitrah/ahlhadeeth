import os, struct, zlib
from io import BytesIO
import pefile
from sfextract.setupfactory7 import SetupFactory7Extractor, ReadSpecialFile, FILENAME_EMBEDDED_INSTALLER, FILENAME_SIZE
from sfextract import SCRIPT_FILE_NAME, decompress, SFFileEntry

DEP_SIZE = 1028368
out = 'extracted'
pe = pefile.PE('zip/setup.exe', fast_load=True)
overlay = BytesIO(pe.get_overlay())
ex = SetupFactory7Extractor((7,), overlay)
overlay.seek(8); overlay.seek(1, os.SEEK_CUR)
ex.files.append(ReadSpecialFile(overlay, out, FILENAME_EMBEDDED_INSTALLER, True))
n = struct.unpack('I', overlay.read(4))[0]
script = None
for _ in range(n):
    name = overlay.read(FILENAME_SIZE).split(b'\x00',1)[0]
    fs, crc = struct.unpack('II', overlay.read(8))
    data = decompress(ex.compression, overlay.read(fs))
    assert crc == zlib.crc32(data), name
    p = os.path.join(out, name.decode())
    open(p,'wb').write(data)
    e = SFFileEntry(name=name, local_path=p, unpacked_size=len(data), packed_size=fs, compression=ex.compression, crc=crc)
    ex.files.append(e)
    if name == SCRIPT_FILE_NAME: script = e
print('data pos', overlay.tell())
dep = overlay.read(DEP_SIZE)
open(os.path.join(out, 'VBRun60sp6.exe'),'wb').write(dep)
ex.ParseScript(script, out)
for f in ex.files:
    if f.crc and os.path.exists(f.local_path):
        ok = zlib.crc32(open(f.local_path,'rb').read()) == f.crc
        print(('OK ' if ok else 'BAD'), f.unpacked_size, f.packed_size, f.name.decode(errors='replace'))
print('end pos', overlay.tell(), 'of', len(pe.get_overlay()))
