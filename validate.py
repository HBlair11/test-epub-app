from pathlib import Path
import re, xml.etree.ElementTree as ET, zipfile, hashlib, sys
root=Path('/mnt/data/v33_debug')
errors=[]
# newline check
for ext in ['.kt','.kts','.xml','.md']:
    for p in root.rglob(f'*{ext}'):
        b=p.read_bytes()
        if b and not b.endswith(b'\n'):
            errors.append(f'no final newline: {p.relative_to(root)}')
# XML parse
for p in root.rglob('*.xml'):
    try: ET.parse(p)
    except Exception as e: errors.append(f'XML parse: {p.relative_to(root)}: {e}')
# resource declarations
res_names=set()
for p in root.glob('app/src/main/res/values/*.xml'):
    try:
        tree=ET.parse(p)
        for e in tree.getroot():
            n=e.attrib.get('name')
            if n: res_names.add(n)
    except: pass
for p in root.glob('app/src/main/res/layout/*.xml'):
    try:
        tree=ET.parse(p)
        for e in tree.getroot().iter():
            rid=e.attrib.get('{http://schemas.android.com/apk/res/android}id','')
            if rid.startswith('@+id/'): res_names.add(rid[5:])
    except: pass
# Kotlin R.string/drawable/layout/id references against declarations (best-effort)
for p in root.rglob('*.kt'):
    s=p.read_text(errors='ignore')
    for typ,name in re.findall(r'R\.(string|drawable|layout|id)\.([A-Za-z_][A-Za-z0-9_]*)',s):
        if name not in res_names:
            # Android framework ids are android.R, not matched by this regex
            errors.append(f'missing resource {typ}/{name} referenced by {p.relative_to(root)}')
# required feature files
required=[
'app/src/main/java/com/epubreader/app/data/HighlightEntity.kt',
'app/src/main/java/com/epubreader/app/epub/DictionaryLookup.kt',
'app/src/main/assets/dict/en.db',
'app/src/main/java/com/epubreader/app/ui/HomeModels.kt',
]
for x in required:
    if not (root/x).exists(): errors.append(f'missing required file: {x}')
# forbidden removed chapter detector / UI
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.xml','.md'}:
        s=p.read_text(errors='ignore')
        if 'EpubChapterDetector' in s or 'homeContinueChapter' in s or 'home_continue_chapter' in s:
            errors.append(f'forbidden legacy chapter-count reference: {p.relative_to(root)}')
# version
bg=(root/'app/build.gradle.kts').read_text()
if 'versionCode = 34' not in bg or 'versionName = "1.33"' not in bg: errors.append('version mismatch')
# missing helpers specifically
ra=(root/'app/src/main/java/com/epubreader/app/ReaderActivity.kt').read_text()
for token in ['private fun colorToHex(', 'private fun themeColor(']:
    if token not in ra: errors.append('missing helper '+token)
# duplicate helper check
if len(re.findall(r'private fun themeColor\(',ra)) != 1: errors.append('themeColor helper count != 1')
# version 12 migration chain
adb=(root/'app/src/main/java/com/epubreader/app/data/AppDatabase.kt').read_text()
for token in ['version = 12','MIGRATION_11_12','addMigrations']:
    if token not in adb: errors.append('missing db marker '+token)
print('VALIDATION', 'PASS' if not errors else 'FAIL')
for e in errors: print(' -',e)
