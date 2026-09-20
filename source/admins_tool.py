#!/usr/bin/env python3
"""أداة إدارة ملف المشرفين admins.json لتطبيق «أهل الحديث والأثر» (للإنشاء الأول والطوارئ).

الصيغة مطابقة لما يستعمله التطبيق (data/Admins.kt):
  key   = PBKDF2-HMAC-SHA256(pin, salt, iterations, 32)
  hash  = base64(HMAC-SHA256(key, b"verify"))
  wrapped = base64(iv ‖ AES-256-GCM(HMAC-SHA256(key, b"enc"), iv, "user\\npass"))

الاستعمال:
  admins_tool.py init  OUT.json --super-user admin --super-name "المشرف العام" --super-pin SUPER-SECRET --nas-user manus --nas-pass '...'
  admins_tool.py add   FILE.json --user ahmad --name "أحمد" --pin ADMIN-SECRET --super-pin SUPER-SECRET
  admins_tool.py check FILE.json --user admin --pin SUPER-SECRET        # يتحقق من الرقم ويطبع مفتاح الخادم
  admins_tool.py list  FILE.json
الرقم السري الجديد ١٢ خانة على الأقل (حروف وأرقام)؛ الأرقام القديمة الأقصر تبقى صالحة في check و --super-pin.
"""
import argparse, base64, hashlib, hmac, json, secrets, time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ITER = 100_000
MIN_PIN = 12  # مطابق لـ AdminCrypto.MIN_PIN: الملف عام فالرقم القصير يُكسر بالتجربة


def norm_pin(pin):
    out = []
    for c in pin.strip():
        if '٠' <= c <= '٩':
            out.append(chr(ord('0') + ord(c) - 0x660))
        elif '۰' <= c <= '۹':
            out.append(chr(ord('0') + ord(c) - 0x6f0))
        elif c.isspace() or c == '-':
            continue
        else:
            out.append(c)
    return ''.join(out)


def derive(pin, salt, iterations=ITER):
    return hashlib.pbkdf2_hmac('sha256', norm_pin(pin).encode(), salt, iterations, 32)


def verifier(key):
    return base64.b64encode(hmac.new(key, b'verify', hashlib.sha256).digest()).decode()


def wrap(key, text):
    k = hmac.new(key, b'enc', hashlib.sha256).digest()
    iv = secrets.token_bytes(12)
    return base64.b64encode(iv + AESGCM(k).encrypt(iv, text.encode(), None)).decode()


def unwrap(key, wrapped):
    k = hmac.new(key, b'enc', hashlib.sha256).digest()
    raw = base64.b64decode(wrapped)
    return AESGCM(k).decrypt(raw[:12], raw[12:], None).decode()


def set_secret(obj, pin, credential, iterations=ITER):
    p = norm_pin(pin)
    if len(p) < MIN_PIN:
        raise SystemExit('الرقم السري قصير (١٢ خانة على الأقل)')
    salt = secrets.token_bytes(16)
    key = derive(p, salt, iterations)
    obj.update(salt=base64.b64encode(salt).decode(), hash=verifier(key), wrapped=wrap(key, credential), updated=int(time.time() * 1000))


def load(path):
    o = json.load(open(path, encoding='utf-8'))
    if o.get('format') != 'ahl-alhadeeth-admins':
        raise SystemExit('ملف غير صالح')
    return o


def save(path, o):
    o['updated'] = int(time.time() * 1000)
    json.dump(o, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)


def iters(o):
    # لا نقبل من الملف عدد دورات أقل من الافتراضي (مطابق لـ AdminRegistry.iterations في التطبيق)
    return max(int(o.get('kdf', {}).get('iterations', ITER)), ITER)


def find(o, user):
    if user == o['super']['user']:
        return o['super']
    for a in o['admins']:
        if a['user'] == user:
            return a
    return None


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('init'); p.add_argument('out'); p.add_argument('--super-user', default='admin'); p.add_argument('--super-name', default='المشرف العام')
    p.add_argument('--super-pin', required=True); p.add_argument('--nas-user', required=True); p.add_argument('--nas-pass', required=True)
    p = sub.add_parser('add'); p.add_argument('file'); p.add_argument('--user', required=True); p.add_argument('--name', required=True); p.add_argument('--pin', required=True); p.add_argument('--super-pin', required=True)
    p = sub.add_parser('check'); p.add_argument('file'); p.add_argument('--user', required=True); p.add_argument('--pin', required=True)
    p = sub.add_parser('list'); p.add_argument('file')
    a = ap.parse_args()
    if a.cmd == 'init':
        o = {'format': 'ahl-alhadeeth-admins', 'version': 1, 'updated': int(time.time() * 1000),
             'kdf': {'alg': 'pbkdf2-hmac-sha256', 'iterations': ITER},
             'super': {'user': a.super_user, 'name': a.super_name}, 'admins': []}
        set_secret(o['super'], a.super_pin, a.nas_user + '\n' + a.nas_pass)
        save(a.out, o)
        print('created', a.out)
    elif a.cmd == 'add':
        o = load(a.file)
        s = o['super']
        key = derive(a.super_pin, base64.b64decode(s['salt']), iters(o))
        if verifier(key) != s['hash']:
            raise SystemExit('رقم المشرف العام غير صحيح')
        cred = unwrap(key, s['wrapped'])
        if find(o, a.user):
            raise SystemExit('اسم المستخدم مستعمل')
        entry = {'user': a.user, 'name': a.name, 'active': True, 'created': int(time.time() * 1000), 'created_by': s['user']}
        set_secret(entry, a.pin, cred, iters(o))
        o['admins'].append(entry)
        save(a.file, o)
        print('added', a.user)
    elif a.cmd == 'check':
        o = load(a.file)
        e = find(o, a.user)
        if not e:
            raise SystemExit('لا يوجد')
        key = derive(a.pin, base64.b64decode(e['salt']), iters(o))
        ok = verifier(key) == e['hash']
        print('pin ok:', ok)
        if ok:
            print('credential user:', unwrap(key, e['wrapped']).split('\n')[0])
    elif a.cmd == 'list':
        o = load(a.file)
        print('super:', o['super']['user'], o['super'].get('name'))
        for x in o['admins']:
            print('admin:', x['user'], x.get('name'), 'active' if x.get('active', True) else 'DISABLED', '' if x.get('wrapped') else '(needs new pin)')


if __name__ == '__main__':
    main()
