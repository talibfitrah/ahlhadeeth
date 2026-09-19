#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""نسخ كل ملفات الصوت لبرنامج «أهل الحديث والأثر» من خادم alathar.net إلى خادم البيانات (NAS) عبر واجهة DSM
بالبنية نفسها التي يتوقعها التطبيق: /downloads/ahl-alhadeeth/sound/<الشيخ>/<الكتاب>/<الملف>.mp3

- يعمل من أي حاسوب (لا يحتاج إلا Python 3.8+)، ويستأنف من حيث توقف: ما وُجد على الخادم بالحجم الصحيح يُتخطّى.
- كل عامل يحتفظ باتصال واحد دائم بـ DSM (جلسة DSM مرتبطة بعنوان IP الطالب).
- الحالة تُحفظ في mirror_state.json محليًّا وعلى الخادم (sound/_mirror_state.json) كي لا تُعاد فحوص الأحجام بلا داعٍ.

الاستعمال:
  python3 mirror_to_nas.py --super-pin 12345678            # المفتاح من admins.json بالرقم السري للمشرف العام
  python3 mirror_to_nas.py --user manus --password …        # أو بحساب الخادم مباشرة
  خيارات: --workers 4  --sheekh 6  --list audio_list.json  --dest /downloads/ahl-alhadeeth/sound
"""
import argparse, base64, hashlib, http.client, json, os, re, signal, ssl, sys, threading, time, urllib.parse, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
API_HOST = 'files.murabbie.org'
SRC_BASE = 'https://www.alathar.net/files/sound/'
ADMINS_URL = 'https://files.murabbie.org/fsdownload/jbktInN4f/admins.json'
UA = 'AhlAlhadeeth-mirror/2.0'
CA = os.environ.get('SSL_CERT_FILE')  # داخل بيئات الوكيل فقط
_ctx = ssl.create_default_context(cafile=CA) if CA else ssl.create_default_context()
_proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or '')

log_lock = threading.Lock()
STOP = threading.Event()  # طلب إيقاف رشيق (SIGTERM/SIGINT): لا تبدأ ملفات جديدة، وأكمل الجاري، واحفظ الحالة


def log(*a):
    with log_lock:
        print(time.strftime('%H:%M:%S'), *a, flush=True)


class Nas:
    """اتصال دائم واحد بواجهة DSM (يُعاد فتحه عند الانقطاع) مع جلسة FileStation"""

    def __init__(self, user, pw):
        self.user, self.pw, self.c, self.sid = user, pw, None, None

    def connect(self):
        if self.c:
            try: self.c.close()
            except Exception: pass
        if _proxy.hostname:
            self.c = http.client.HTTPSConnection(_proxy.hostname, _proxy.port, timeout=900, context=_ctx)
            self.c.set_tunnel(API_HOST, 443)
        else:
            self.c = http.client.HTTPSConnection(API_HOST, 443, timeout=900, context=_ctx)
        self.sid = None

    def _req(self, method, path, body=None, headers=None):
        if not self.c: self.connect()
        h = {'User-Agent': UA, 'Connection': 'keep-alive'}
        if headers: h.update(headers)
        try:
            self.c.request(method, path, body=body, headers=h)
            r = self.c.getresponse(); data = r.read()
            return json.loads(data)
        except Exception:
            self.connect(); raise

    def login(self):
        q = urllib.parse.urlencode({'api': 'SYNO.API.Auth', 'version': 3, 'method': 'login', 'account': self.user, 'passwd': self.pw, 'session': 'FileStation', 'format': 'sid'})
        d = self._req('GET', '/webapi/auth.cgi?' + q)
        if not d.get('success'): raise RuntimeError('دخول الخادم فشل: %s' % d)
        self.sid = d['data']['sid']

    def api(self, name, version, method, **params):
        last = None
        for attempt in range(8):
            try:
                if not self.sid: self.login()
                q = urllib.parse.urlencode(dict(api=name, version=version, method=method, _sid=self.sid, **params))
                d = self._req('GET', '/webapi/entry.cgi?' + q)
                if d.get('success'): return d
                code = d.get('error', {}).get('code')
                if code in (119, 105, 106, 107): self.sid = None; continue
                raise RuntimeError('خطأ %s من %s' % (code, name))
            except RuntimeError:
                raise
            except Exception as e:
                last = e; time.sleep(2 * (attempt + 1))
        raise RuntimeError('تعذر الاتصال بالخادم: %s' % last)

    def listdir(self, path):
        out, offset = {}, 0
        while True:
            try:
                d = self.api('SYNO.FileStation.List', 2, 'list', folder_path=path, additional='["size"]', offset=offset, limit=1000)
            except RuntimeError as e:
                if '408' in str(e) or '418' in str(e): return {}  # المجلد غير موجود بعد
                raise
            files = d['data']['files']
            for f in files: out[f['name']] = -1 if f['isdir'] else f['additional']['size']
            if len(files) < 1000: break
            offset += 1000
        return out

    def size_of(self, path):
        try:
            d = self.api('SYNO.FileStation.List', 2, 'getinfo', path=json.dumps([path]), additional='["size"]')
            f = d['data']['files'][0]
            if f.get('code'): return -1
            return f['additional']['size']
        except Exception:
            return -1

    def upload(self, local, folder, name, mime='audio/mpeg'):
        size = os.path.getsize(local)
        last = None
        for attempt in range(6):
            try:
                if not self.sid: self.login()
                b = '----AhlAlhadeeth' + uuid.uuid4().hex
                head = b''
                for k, v in [('path', folder), ('create_parents', 'true'), ('overwrite', 'true')]:
                    head += ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (b, k, v)).encode()
                head += ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n' % (b, name, mime)).encode()
                tail = ('\r\n--%s--\r\n' % b).encode()

                def gen():
                    yield head
                    with open(local, 'rb') as f:
                        while True:
                            chunk = f.read(1024 * 1024)
                            if not chunk: break
                            yield chunk
                    yield tail
                d = self._req('POST', '/webapi/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=' + self.sid, body=gen(),
                              headers={'Content-Type': 'multipart/form-data; boundary=' + b, 'Content-Length': str(len(head) + size + len(tail))})
                if d.get('success'): return True
                code = d.get('error', {}).get('code')
                if code in (119, 105, 106, 107): self.sid = None; continue
                last = 'خطأ %s' % code
            except Exception as e:
                last = e
            time.sleep(3 * (attempt + 1))
        log('  تعذر الرفع', name, last)
        return False


def fetch_share(url, tries=10):
    m = re.match(r'^(https?://[^/]+)/fsdownload/([^/]+)/', url)
    import http.cookiejar
    for i in range(tries):
        try:
            cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)); op.addheaders = [('User-Agent', UA)]
            if m: op.open(m.group(1) + '/sharing/' + m.group(2), timeout=60).read()
            return op.open(url, timeout=120).read().decode('utf-8')
        except Exception:
            time.sleep(2 + i)
    raise SystemExit('تعذر جلب ' + url)


def credentials(a):
    if a.user and a.password: return a.user, a.password
    if not a.super_pin: raise SystemExit('يلزم --super-pin أو --user/--password')
    sys.path.insert(0, HERE)
    import admins_tool as at
    reg = json.loads(fetch_share(ADMINS_URL))
    entry = at.find(reg, a.admin)
    if not entry: raise SystemExit('لا يوجد مشرف ' + a.admin)
    key = at.derive(a.super_pin, base64.b64decode(entry['salt']), reg['kdf']['iterations'])
    if at.verifier(key) != entry['hash']: raise SystemExit('الرقم السري غير صحيح')
    lines = at.unwrap(key, entry['wrapped']).split('\n')
    return lines[0], lines[1]


def head_size(url):
    for i in range(4):
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, method='HEAD', headers={'User-Agent': UA}), timeout=60)
            return int(r.headers.get('Content-Length') or 0)
        except urllib.error.HTTPError as e:
            if e.code == 404: return -1
        except Exception:
            pass
        time.sleep(2 * (i + 1))
    return -2


def download(url, dest, retries=6):
    """تنزيل مع استئناف؛ يعيد الحجم أو -1 (غير موجود) أو -2 (فشل)"""
    tmp = dest + '.part'
    if os.path.exists(dest) and not os.path.exists(tmp):
        real = head_size(url)
        if real == os.path.getsize(dest): return real  # نُزِّل سابقًا ولم يُرفع
        os.remove(dest)
    for attempt in range(retries):
        have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        if have > 0: req.add_header('Range', 'bytes=%d-' % have)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                if resp.getcode() == 200 and have > 0: have = 0
                total = None
                cr = resp.headers.get('Content-Range')
                if cr and '/' in cr: total = int(cr.split('/')[-1])
                elif resp.headers.get('Content-Length'): total = have + int(resp.headers['Content-Length'])
                with open(tmp, 'r+b' if have > 0 else 'wb') as f:
                    f.seek(have)
                    while True:
                        chunk = resp.read(512 * 1024)
                        if not chunk: break
                        f.write(chunk); have += len(chunk)
                if total is not None and have < total: raise IOError('incomplete %d/%d' % (have, total))
            os.replace(tmp, dest)
            return have
        except urllib.error.HTTPError as e:
            if e.code == 416:
                os.replace(tmp, dest); return have
            if e.code == 404: return -1
            time.sleep(3 * (attempt + 1))
        except Exception:
            time.sleep(3 * (attempt + 1))
    return -2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', default=os.path.join(HERE, 'build', 'audio_list.json') if os.path.exists(os.path.join(HERE, 'build', 'audio_list.json')) else os.path.join(HERE, 'audio_list.json'))
    ap.add_argument('--dest', default='/downloads/ahl-alhadeeth/sound')
    ap.add_argument('--tmp', default=os.path.join(HERE, 'mirror-tmp'))
    ap.add_argument('--state', default=os.path.join(HERE, 'mirror_state.json'))
    ap.add_argument('--workers', type=int, default=4)
    ap.add_argument('--sheekh', type=int, default=0)
    ap.add_argument('--limit', type=int, default=0, help='عدد الملفات (للتجربة)')
    ap.add_argument('--admin', default='admin'); ap.add_argument('--super-pin', dest='super_pin')
    ap.add_argument('--user'); ap.add_argument('--password')
    a = ap.parse_args()
    user, pw = credentials(a)
    os.makedirs(a.tmp, exist_ok=True)

    items = json.load(open(a.list, encoding='utf-8'))
    seen = set(); uniq = []
    for it in items:
        if it['rel'] in seen: continue
        seen.add(it['rel']); uniq.append(it)
    items = uniq
    if a.sheekh: items = [i for i in items if i['sheekh_id'] == a.sheekh]
    if a.limit: items = items[:a.limit]

    state = json.load(open(a.state, encoding='utf-8')) if os.path.exists(a.state) else {}
    state_lock = threading.Lock()

    def save_state():
        with state_lock:
            tmp = a.state + '.tmp'
            json.dump(state, open(tmp, 'w', encoding='utf-8'), ensure_ascii=False)
            os.replace(tmp, a.state)

    # ١) قائمة ما على الخادم (للمجلدات التي فيها ملفات بلا سجل في الحالة فقط)
    main_nas = Nas(user, pw); main_nas.login(); log('دخول الخادم ✓ —', len(items), 'ملف —', sum(1 for i in items if state.get(i['rel'])), 'في السجل')
    dirs = sorted(set(i['rel'].rsplit('/', 1)[0] for i in items if not state.get(i['rel'])))
    remote = {}
    for i, d in enumerate(dirs):
        if STOP.is_set(): break
        remote[d] = main_nas.listdir(a.dest + '/' + d)
    log('قوائم الخادم:', len(dirs), 'مجلدًا —', sum(len(v) for v in remote.values()), 'ملفًا موجودًا')
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    signal.signal(signal.SIGINT, lambda *_: STOP.set())

    counters = {'done': 0, 'skipped': 0, 'missing': 0, 'failed': 0, 'bytes': 0}
    counters_lock = threading.Lock()
    t0 = time.time()
    local = threading.local()

    def nas_for_thread():
        if not hasattr(local, 'nas'):
            local.nas = Nas(user, pw); local.nas.login()
        return local.nas

    def bump(k, n=1):
        with counters_lock: counters[k] += n

    def work(it):
        if STOP.is_set(): return
        rel = it['rel']; d, name = rel.rsplit('/', 1)
        url = SRC_BASE + rel
        st = state.get(rel) or {}
        on_nas = remote.get(d, {}).get(name, 0)
        if st.get('missing'):
            bump('missing'); return
        if st.get('size'):
            bump('skipped'); return  # في السجل: رُفع وتحقق حجمه من قبل
        if on_nas > 0 and not st.get('size'):
            # موجود على الخادم بلا سجل: قارن بالحجم الأصلي
            real = head_size(url)
            if real == on_nas:
                with state_lock: state[rel] = {'size': real}
                bump('skipped'); return
            if real == -1:
                with state_lock: state[rel] = {'missing': True}
                bump('missing'); return
        local_path = os.path.join(a.tmp, rel.replace('/', '__'))
        size = download(url, local_path)
        if size == -1:
            with state_lock: state[rel] = {'missing': True}
            bump('missing'); log('  غير موجود على alathar:', rel); return
        if size < 0:
            bump('failed'); log('  فشل التنزيل:', rel); return
        nas = nas_for_thread()
        ok = nas.upload(local_path, a.dest + '/' + d, name)
        got = nas.size_of(a.dest + '/' + rel) if ok else -1
        if ok and got == size:
            with state_lock: state[rel] = {'size': size}
            bump('done'); bump('bytes', size)
            try: os.remove(local_path)
            except OSError: pass
        else:
            bump('failed'); log('  حجم غير مطابق بعد الرفع:', rel, got, '!=', size)
        n = counters['done'] + counters['skipped'] + counters['missing'] + counters['failed']
        if n % 25 == 0:
            el = time.time() - t0
            log('%d/%d — تم %d، متخطى %d، مفقود %d، فشل %d — %.1f ج.ب — %.1f م.ب/ث' % (n, len(items), counters['done'], counters['skipped'], counters['missing'], counters['failed'], counters['bytes'] / 1e9, counters['bytes'] / 1e6 / max(el, 1)))
        if n % 20 == 0: save_state()

    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        list(ex.map(work, items))
    save_state()
    el = time.time() - t0
    left = sum(1 for i in items if not (state.get(i['rel']) or {}).get('size') and not (state.get(i['rel']) or {}).get('missing'))
    log(('أُوقف مؤقتًا' if STOP.is_set() else 'انتهى') + ': تم %d، متخطى %d، مفقود %d، فشل %d — %.1f ج.ب في %.0f دقيقة — بقي %d' % (counters['done'], counters['skipped'], counters['missing'], counters['failed'], counters['bytes'] / 1e9, el / 60, left))
    if STOP.is_set(): return
    # نسخة من الحالة على الخادم
    try:
        main_nas.upload(a.state, a.dest, '_mirror_state.json', 'application/json')
    except Exception as e:
        log('تعذر رفع ملف الحالة', e)
    # ملخص
    missing = [r for r, s in state.items() if s.get('missing')]
    summary = {'files': len(items), 'done_total': sum(1 for r, s in state.items() if s.get('size')), 'missing': missing, 'bytes_total': sum(s.get('size', 0) for s in state.values()), 'updated': time.strftime('%Y-%m-%d %H:%M')}
    json.dump(summary, open(os.path.join(HERE, 'mirror_summary.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    if counters['failed']: sys.exit(1)


if __name__ == '__main__':
    main()
