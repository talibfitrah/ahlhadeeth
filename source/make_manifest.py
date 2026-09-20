#!/usr/bin/env python3
"""توليد manifest.json لتطبيق أهل الحديث والأثر من روابط المشاركة وملف SHA256SUMS."""
import json, os
BUILD = '/home/claude/alathar/build'
NAS_AUDIO_TEMPLATE = 'https://files.murabbie.org/webapi/entry.cgi?api=SYNO.FolderSharing.Download&version=2&method=download&mode=open&_sharing_id=ehniFEwJg&path=%5B%22%2Fdownloads%2Fahl-alhadeeth%2Fsound%2F{path}%22%5D'
sums = {}
for line in open(os.path.join(BUILD, 'SHA256SUMS.txt')):
    h, name = line.split()
    sums[name] = h
parts = [json.loads(l) for l in open(os.path.join(BUILD, 'shares_parts.jsonl'))]
parts.sort(key=lambda l: l['name'])
apk = {}
apk_page = {}
if os.path.exists(os.path.join(BUILD, 'shares_apk.json')):
    apk = json.load(open(os.path.join(BUILD, 'shares_apk.json')))
    for k, u in apk.items():
        sid = u.split('/fsdownload/')[1].split('/')[0]
        apk_page[k] = 'https://files.murabbie.org/sharing/' + sid
db_size = os.path.getsize(os.path.join(BUILD, 'ahl_alhadeeth.db'))
gz_size = os.path.getsize(os.path.join(BUILD, 'ahl_alhadeeth.db.gz'))
manifest = {
    'version': 1,
    'name': 'أهل الحديث والأثر',
    'updated': __import__('time').strftime('%Y-%m-%d'),
    'app': {
        'version_code': int(os.environ['APP_VERSION_CODE']),
        'version_name': os.environ['APP_VERSION_NAME'],
        'apk': apk,
        'apk_page': apk_page,
        'notes': os.environ.get('APP_NOTES', ''),
    },
    'data': {
        'id': 'ahl_alhadeeth',
        'version': '4.14.0-1',
        'file': 'ahl_alhadeeth.db.gz',
        'description': 'قاعدة بيانات برنامج أهل الحديث والأثر ٤٫١٤٫٠: ١٠ مشايخ، ٥٥ كتابًا، ١٣٬٧١٦ شريطًا، ٢٦٢٬٥٨٩ مقطعًا، ١١١٬٥٨٠ تفريغًا، ١٬١٩٦ تصنيفًا',
        'size_gz': gz_size,
        'size_db': db_size,
        'sha256_gz': sums['ahl_alhadeeth.db.gz'],
        'sha256_db': sums['ahl_alhadeeth.db'],
        'parts': [
            {'url': 'https://files.murabbie.org/fsdownload/%s/%s' % (l['id'], l['name']),
             'alt_url': 'http://nas.fitrahmedia.nl:5000/fsdownload/%s/%s' % (l['id'], l['name']),
             'size': os.path.getsize(os.path.join(BUILD, l['name'])),
             'sha256': sums[l['name']]}
            for l in parts
        ],
    },
    'packs': json.load(open(os.path.join(BUILD, 'packs.json'), encoding='utf-8')) if os.path.exists(os.path.join(BUILD, 'packs.json')) else [],
    # المحتوى المشترك الذي ينشره المشرفون (رابط مشاركة دائم لملف shared-content.json + واجهة DSM للنشر)
    'shared': {
        'url': open(os.path.join(BUILD, 'shared_url.txt')).read().strip() if os.path.exists(os.path.join(BUILD, 'shared_url.txt')) else '',
        # ملف المشرفين (المشرف العام والمشرفون وأرقامهم السرية مبصومة، ومفتاح الخادم مغلَّفًا)
        'admins_url': open(os.path.join(BUILD, 'admins_url.txt')).read().strip() if os.path.exists(os.path.join(BUILD, 'admins_url.txt')) else '',
        'admins_file': 'admins.json',
        'api': 'https://files.murabbie.org/webapi',
        'path': '/downloads/ahl-alhadeeth',
        'file': 'shared-content.json',
    },
    'audio': {
        'ext': '.mp3',
        'servers': [
            {'title': 'خادم أهل الحديث والأثر (alathar.net)', 'url': 'https://www.alathar.net/files/sound/'},
        ],
        # منذ ١٫٧٫٠: خوادم بقالب فيه {path} (تتجاهلها النسخ الأقدم) — نسخة الصوت الكاملة على خادم البيانات عبر مشاركة المجلد الدائمة ehniFEwJg
        'servers_v2': [
            {'title': 'خادم البيانات (NAS) — نسخة كاملة من الصوت', 'url': NAS_AUDIO_TEMPLATE},
        ],
        # الخادم الافتراضي لمن لم يختر خادمًا بنفسه (يُضبط بعد اكتمال النسخ)
        'default_server': os.environ.get('AUDIO_DEFAULT_SERVER', ''),
    },
}
out = os.path.join(BUILD, 'manifest.json')
json.dump(manifest, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(open(out, encoding='utf-8').read()[:1500])
