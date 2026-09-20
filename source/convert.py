#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""تحويل قواعد Access لبرنامج «أهل الحديث والأثر» ٤٫١٤٫٠ إلى قاعدة SQLite واحدة مع فهرس FTS5."""
import csv, os, re, sqlite3, sys, time

csv.field_size_limit(10**9)
SRC = '/home/claude/alathar/csv'
OUT = '/home/claude/alathar/build/ahl_alhadeeth.db'
os.makedirs(os.path.dirname(OUT), exist_ok=True)
if os.path.exists(OUT):
    os.remove(OUT)

# ---------- تطبيع النص العربي (يجب أن يطابق ArabicNormalizer.kt في التطبيق) ----------
_DIAC = re.compile('[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640\u200B-\u200F\u202A-\u202E\uFEFF]')
_MAP = {
    'أ': 'ا', 'إ': 'ا', 'آ': 'ا', 'ٱ': 'ا',  # أ إ آ ٱ -> ا
    'ى': 'ي', 'ی': 'ي', 'ئ': 'ي',                     # ى ی ئ -> ي
    'ة': 'ه',                                                             # ة -> ه
    'ؤ': 'و',                                                             # ؤ -> و
    'ک': 'ك',                                                             # ک -> ك
    'گ': 'ك',
}
for i in range(10):
    _MAP[chr(0x660 + i)] = chr(0x30 + i)   # ٠-٩
    _MAP[chr(0x6f0 + i)] = chr(0x30 + i)   # ۰-۹
_TRANS = str.maketrans(_MAP)


def norm(s):
    if not s:
        return ''
    s = _DIAC.sub('', s)
    s = s.translate(_TRANS)
    return s.lower()


def to_int(v, default=0):
    try:
        return int(str(v).strip())
    except Exception:
        try:
            return int(float(str(v).strip()))
        except Exception:
            return default


def rows(name):
    with open(os.path.join(SRC, name), encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f):
            yield r


t0 = time.time()
db = sqlite3.connect(OUT)
db.execute('PRAGMA journal_mode=OFF')
db.execute('PRAGMA synchronous=OFF')
db.execute('PRAGMA page_size=4096')
db.executescript('''
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE sheekh(id INTEGER PRIMARY KEY, name TEXT NOT NULL, is_default INTEGER NOT NULL DEFAULT 1, ord INTEGER NOT NULL DEFAULT 0);
CREATE TABLE type(id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0);
CREATE TABLE book(id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0, type_id INTEGER NOT NULL DEFAULT 0);
CREATE TABLE chapter(
  code INTEGER PRIMARY KEY, sheekh_id INTEGER NOT NULL, book_id INTEGER NOT NULL,
  title TEXT NOT NULL, file_name TEXT NOT NULL, file_size INTEGER NOT NULL DEFAULT 0,
  duration INTEGER NOT NULL DEFAULT 0, path TEXT NOT NULL, cd_number TEXT NOT NULL DEFAULT '',
  ord INTEGER NOT NULL DEFAULT 0, seg_count INTEGER NOT NULL DEFAULT 0, write_count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE category(id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL DEFAULT 0,
  parent INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 1,
  direct_count INTEGER NOT NULL DEFAULT 0, total_count INTEGER NOT NULL DEFAULT 0, child_count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE content(
  id INTEGER PRIMARY KEY, code INTEGER NOT NULL, seq INTEGER NOT NULL,
  sheekh_id INTEGER NOT NULL, book_id INTEGER NOT NULL, hnum INTEGER NOT NULL DEFAULT 0,
  line TEXT NOT NULL, offset_start INTEGER NOT NULL DEFAULT 0, offset_end INTEGER NOT NULL DEFAULT 0,
  write TEXT, ques INTEGER NOT NULL DEFAULT 0);
CREATE TABLE content_cat(content_id INTEGER NOT NULL, category_id INTEGER NOT NULL, PRIMARY KEY(category_id, content_id)) WITHOUT ROWID;
CREATE VIRTUAL TABLE content_fts USING fts5(line, write, content='', tokenize='unicode61');
''')

# ---------- الجداول المرجعية ----------
db.executemany('INSERT INTO sheekh VALUES (?,?,?,?)',
               [(to_int(r['Sheekh_id']), r['Sheekh_name'].strip(), 1 if r['Sheekh_default'].strip() == 'default' else 0, to_int(r['Sheekh_order']))
                for r in rows('Sheekh.csv')])
db.executemany('INSERT INTO type VALUES (?,?,?)',
               [(to_int(r['Type_id']), r['Type_name'].strip(), to_int(r['Type_order'])) for r in rows('Type.csv')])
db.executemany('INSERT INTO book VALUES (?,?,?,?)',
               [(to_int(r['Book_id']), r['Book_name'].strip(), to_int(r['Book_order']), to_int(r['Type_id'])) for r in rows('Book.csv')])
db.executemany('INSERT INTO category(id,name,ord,parent,level) VALUES (?,?,?,?,?)',
               [(to_int(r['Category_id']), r['Category_name'].strip(), to_int(r['Category_order']), to_int(r['Category_parent']), to_int(r['Category_level']))
                for r in rows('Category.csv')])

chap = {}
for r in rows('Chapters.csv'):
    code = to_int(r['Code'])
    chap[code] = (to_int(r['Sheekh_id']), to_int(r['Book_id']))
    db.execute('INSERT INTO chapter(code,sheekh_id,book_id,title,file_name,file_size,duration,path,cd_number,ord) VALUES (?,?,?,?,?,?,?,?,?,?)',
               (code, to_int(r['Sheekh_id']), to_int(r['Book_id']), r['Title'].strip(), r['FileName'].strip(),
                to_int(r['FileSize']), to_int(r['Duration']), r['Path'].strip().replace('\\', '/'), r['CDNumber'].strip(), to_int(r['Chapters_order'])))
print('reference tables done', round(time.time() - t0, 1), 's', flush=True)

# ---------- المحتويات ----------
content_id = 0
idmap = {}          # (code, seq) -> id
missing_chapter = 0
batch = []
fts_batch = []


def flush():
    global batch, fts_batch
    if batch:
        db.executemany('INSERT INTO content VALUES (?,?,?,?,?,?,?,?,?,?,?)', batch)
        db.executemany('INSERT INTO content_fts(rowid, line, write) VALUES (?,?,?)', fts_batch)
        batch = []
        fts_batch = []


for i in range(1, 11):
    name = 'Contents_%03d.csv' % i
    n = 0
    for r in rows(name):
        code = to_int(r['Code'])
        seq = to_int(r['Seq'])
        key = (code, seq)
        if key in idmap:
            continue  # تكرار
        sh = to_int(r['SheekhId'])
        bk = to_int(r['Bookid'])
        if code in chap:
            csh, cbk = chap[code]
            if sh == 0:
                sh = csh
            if bk == 0:
                bk = cbk
        else:
            missing_chapter += 1
        content_id += 1
        idmap[key] = content_id
        line = (r['Line'] or '').strip()
        write = (r['Write'] or '').strip()
        batch.append((content_id, code, seq, sh, bk, to_int(r['HNum']), line,
                      to_int(r['OffsetStart']), to_int(r['OffsetEnd']), write if write else None, to_int(r['Ques'])))
        fts_batch.append((content_id, norm(line), norm(write)))
        n += 1
        if len(batch) >= 2000:
            flush()
    flush()
    print(name, n, 'rows; total', content_id, round(time.time() - t0, 1), 's', flush=True)
print('contents without chapter:', missing_chapter)

# ---------- ربط التصانيف ----------
cc = 0
bad = 0
cat_ids = set(x[0] for x in db.execute('SELECT id FROM category'))
seen = set()
batch = []
for i in range(1, 11):
    for r in rows('ContentCat_%03d.csv' % i):
        key = (to_int(r['Code']), to_int(r['Seq']))
        cid = idmap.get(key)
        cat = to_int(r['Category_id'])
        if cid is None or cat not in cat_ids:
            bad += 1
            continue
        k2 = (cid, cat)
        if k2 in seen:
            continue
        seen.add(k2)
        batch.append((cid, cat))
        cc += 1
        if len(batch) >= 5000:
            db.executemany('INSERT INTO content_cat VALUES (?,?)', batch)
            batch = []
if batch:
    db.executemany('INSERT INTO content_cat VALUES (?,?)', batch)
print('content_cat', cc, 'bad', bad, round(time.time() - t0, 1), 's', flush=True)

# ---------- الفهارس والإحصاءات ----------
db.executescript('''
CREATE UNIQUE INDEX content_code_seq ON content(code, seq);
CREATE INDEX content_sb ON content(sheekh_id, book_id);
CREATE INDEX content_cat_content ON content_cat(content_id);
CREATE INDEX chapter_sb ON chapter(sheekh_id, book_id, ord, title, file_name);
CREATE INDEX category_parent ON category(parent, ord);
UPDATE chapter SET seg_count = (SELECT COUNT(*) FROM content WHERE content.code = chapter.code),
                   write_count = (SELECT COUNT(*) FROM content WHERE content.code = chapter.code AND write IS NOT NULL);
UPDATE category SET direct_count = (SELECT COUNT(*) FROM content_cat WHERE category_id = category.id),
                    child_count = (SELECT COUNT(*) FROM category c2 WHERE c2.parent = category.id);
''')
# total_count = عدد المقاطع المتميزة في الفرع كله (الصنف وفروعه)، direct_count = المقاطع المرتبطة بالصنف نفسه
cats = {cid: parent for cid, parent in db.execute('SELECT id, parent FROM category')}
children = {}
for cid, parent in cats.items():
    children.setdefault(parent, []).append(cid)
direct_sets = {}
for cat, cid in db.execute('SELECT category_id, content_id FROM content_cat'):
    direct_sets.setdefault(cat, set()).add(cid)
total = {}


def calc(cid):
    acc = set(direct_sets.get(cid, ()))
    for ch in children.get(cid, []):
        acc |= calc(ch)
    total[cid] = len(acc)
    return acc


sys.setrecursionlimit(10000)
for root in children.get(0, []):
    calc(root)
for cid in cats:
    if cid not in total:
        calc(cid)
db.executemany('UPDATE category SET total_count = ? WHERE id = ?', [(v, k) for k, v in total.items()])

stats = {
    'sheekh': db.execute('SELECT COUNT(*) FROM sheekh').fetchone()[0],
    'book': db.execute('SELECT COUNT(*) FROM book').fetchone()[0],
    'chapter': db.execute('SELECT COUNT(*) FROM chapter').fetchone()[0],
    'content': db.execute('SELECT COUNT(*) FROM content').fetchone()[0],
    'write': db.execute('SELECT COUNT(*) FROM content WHERE write IS NOT NULL').fetchone()[0],
    'category': db.execute('SELECT COUNT(*) FROM category').fetchone()[0],
    'content_cat': db.execute('SELECT COUNT(*) FROM content_cat').fetchone()[0],
}
print(stats)
meta = {
    'schema_version': '1',
    'source': 'alathar.net setup-4-14-0 (2024-06-15)',
    'source_version': '4.14.0',
    'built_at': time.strftime('%Y-%m-%d'),
    'audio_base_url': 'https://www.alathar.net/files/sound/',
    'audio_ext': '.mp3',
}
meta.update({'count_' + k: str(v) for k, v in stats.items()})
db.executemany('INSERT INTO meta VALUES (?,?)', list(meta.items()))
db.commit()
db.execute("INSERT INTO content_fts(content_fts) VALUES('optimize')")
db.commit()
db.execute('VACUUM')
db.close()
print('done', round(time.time() - t0, 1), 's; size', os.path.getsize(OUT))
