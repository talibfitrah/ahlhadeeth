#!/usr/bin/env python3
"""نقل سلسلة يوتيوب إلى خادم البيانات (NAS) من حاسوب — بديل عن النقل من داخل التطبيق.

يتطلب: python3، yt-dlp (pip install yt-dlp)، ffmpeg. يعمل من شبكة منزلية (يوتيوب يمنع مراكز البيانات).
لكل فيديو في القائمة: تنزيل الصوت m4a (أو الفيديو 360p مع --video)، رفعه إلى
  /downloads/ahl-alhadeeth/media/<slug>/NNN-<id>.m4a، إنشاء رابط مشاركة دائم، ثم تحديث shared-content.json
(الشيخ والسلسلة يُنشآن إن لم يوجدا؛ الدرس الموجود بمفتاح t-yt-<id> أو بمصدر يوتيوب نفسه يُحدَّث لا يُكرَّر) ونشره للجميع.

الاستعمال:
  yt2nas.py --playlist "https://www.youtube.com/playlist?list=..." --sheekh "الشيخ …" --series "شرح …" --super-pin 12345678 [--video] [--slug tawheed]
  (أو --admin USER --pin PIN لمشرف عادي)
يُحفظ التقدم في yt2nas-state-<slug>.json فيمكن إعادة التشغيل للمتابعة.
"""
import argparse, base64, hashlib, json, os, re, subprocess, sys, time, urllib.parse, urllib.request, uuid, http.cookiejar

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import admins_tool as at

API = 'https://files.murabbie.org/webapi'
ROOT = '/downloads/ahl-alhadeeth'
ADMINS_URL = 'https://files.murabbie.org/fsdownload/jbktInN4f/admins.json'
SHARED_URL = 'https://files.murabbie.org/fsdownload/4aPZIQpaW/shared-content.json'
UA = 'AhlAlhadeeth-yt2nas/1.0'


def fetch_share(url, tries=10):
    """جلب ملف عبر رابط مشاركة DSM (تهيئة جلسة المشاركة أولًا)"""
    m = re.match(r'^(https?://[^/]+)/fsdownload/([^/]+)/', url)
    for i in range(tries):
        try:
            cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)); op.addheaders = [('User-Agent', UA)]
            if m: op.open(m.group(1) + '/sharing/' + m.group(2), timeout=60).read()
            return op.open(url, timeout=120).read().decode('utf-8')
        except Exception as e:
            time.sleep(2 + i)
    raise SystemExit('تعذر جلب ' + url)


class Nas:
    def __init__(self, user, pw):
        self.user, self.pw, self.sid = user, pw, None

    def _get(self, url):
        return json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=120).read())

    def login(self):
        d = self._get(API + '/auth.cgi?' + urllib.parse.urlencode({'api': 'SYNO.API.Auth', 'version': '3', 'method': 'login', 'account': self.user, 'passwd': self.pw, 'session': 'FileStation', 'format': 'sid'}))
        if not d.get('success'): raise SystemExit('دخول الخادم فشل: %s' % d)
        self.sid = d['data']['sid']

    def api(self, name, version, method, **params):
        for attempt in range(5):
            if not self.sid: self.login()
            p = dict(api=name, version=version, method=method, _sid=self.sid, **params)
            d = self._get(API + '/entry.cgi?' + urllib.parse.urlencode(p))
            if d.get('success'): return d
            if d.get('error', {}).get('code') in (119, 105, 106, 107): self.sid = None; time.sleep(1); continue
            raise SystemExit('خطأ من الخادم: %s' % d)
        raise SystemExit('تعذر الاتصال بالخادم')

    def upload(self, local, folder, name, mime='application/octet-stream'):
        data = open(local, 'rb').read()
        for attempt in range(5):
            if not self.sid: self.login()
            b = '----AhlAlhadeeth' + uuid.uuid4().hex
            body = b''
            for k, v in [('path', folder), ('create_parents', 'true'), ('overwrite', 'true')]:
                body += ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (b, k, v)).encode()
            body += ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n' % (b, name, mime)).encode() + data + ('\r\n--%s--\r\n' % b).encode()
            req = urllib.request.Request(API + '/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=' + self.sid, data=body, method='POST', headers={'Content-Type': 'multipart/form-data; boundary=' + b, 'User-Agent': UA})
            try:
                d = json.loads(urllib.request.urlopen(req, timeout=3600).read())
            except Exception as e:
                d = {'exc': str(e)}
            if d.get('success'): return folder + '/' + name
            if d.get('error', {}).get('code') in (119, 105, 106, 107): self.sid = None; time.sleep(2); continue
            print('  محاولة الرفع فشلت:', d); time.sleep(5)
        raise SystemExit('تعذر رفع ' + name)

    def share(self, path):
        d = self.api('SYNO.FileStation.Sharing', 3, 'create', path=json.dumps([path]))
        link = d['data']['links'][0]
        return link['url'].split('/sharing/')[0] + '/fsdownload/' + link['id'] + '/' + urllib.parse.quote(path.rsplit('/', 1)[1])


def ytdlp_json(args):
    p = subprocess.run(['yt-dlp', '--no-warnings', '-J'] + args, capture_output=True, text=True)
    if p.returncode != 0: raise RuntimeError(p.stderr.strip()[-300:])
    return json.loads(p.stdout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--playlist', required=True)
    ap.add_argument('--sheekh', required=True); ap.add_argument('--series', required=True); ap.add_argument('--type', default='')
    ap.add_argument('--slug', default='')
    ap.add_argument('--video', action='store_true', help='فيديو 360p mp4 بدل الصوت')
    ap.add_argument('--admin', default='admin'); ap.add_argument('--pin', dest='pin'); ap.add_argument('--super-pin', dest='super_pin')
    ap.add_argument('--workdir', default='yt2nas-work')
    ap.add_argument('--no-publish', action='store_true')
    a = ap.parse_args()
    pin = a.super_pin or a.pin
    if not pin: raise SystemExit('يلزم --super-pin أو --pin')

    # ١) مفتاح الخادم من admins.json بالرقم السري
    reg = json.loads(fetch_share(ADMINS_URL))
    entry = at.find(reg, a.admin)
    if not entry: raise SystemExit('لا يوجد مشرف ' + a.admin)
    key = at.derive(pin, base64.b64decode(entry['salt']), reg['kdf']['iterations'])
    if at.verifier(key) != entry['hash']: raise SystemExit('الرقم السري غير صحيح')
    lines = at.unwrap(key, entry['wrapped']).split('\n')
    nas = Nas(lines[0], lines[1])
    nas.login(); print('دخول الخادم ✓')

    # ٢) قائمة التشغيل
    pl = ytdlp_json(['--flat-playlist', a.playlist])
    entries = [e for e in pl.get('entries', []) if e.get('id')]
    if not entries: raise SystemExit('القائمة فارغة')
    slug = a.slug or re.sub(r'[^A-Za-z0-9_-]', '', 'pl-' + hashlib.sha1((pl.get('id') or a.playlist).encode()).hexdigest()[:10])
    print('القائمة: %s — %d فيديو — المجلد media/%s' % (pl.get('title'), len(entries), slug))
    os.makedirs(a.workdir, exist_ok=True)
    state_path = os.path.join(a.workdir, 'yt2nas-state-%s.json' % slug)
    state = json.load(open(state_path, encoding='utf-8')) if os.path.exists(state_path) else {}

    # ٣) لكل فيديو: تنزيل → رفع → رابط
    folder = ROOT + '/media/' + slug
    for i, e in enumerate(entries, 1):
        vid = e['id']
        if vid in state and state[vid].get('link'):
            print('%03d %s: تم سابقًا' % (i, vid)); continue
        title = e.get('title') or ('درس %d' % i)
        ext = 'mp4' if a.video else 'm4a'
        name = '%03d-%s.%s' % (i, vid, ext)
        local = os.path.join(a.workdir, name)
        if not os.path.exists(local) or os.path.getsize(local) < 10000:
            fmt = 'best[height<=360][ext=mp4]/bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]' if a.video else '140/bestaudio[ext=m4a]/bestaudio'
            for attempt in range(8):
                p = subprocess.run(['yt-dlp', '--no-warnings', '-q', '-f', fmt, '--merge-output-format', 'mp4', '--continue', '--retries', '20', '--fragment-retries', '50', '-o', local, 'https://www.youtube.com/watch?v=' + vid], capture_output=True, text=True)
                if p.returncode == 0 and os.path.exists(local): break
                print('  تنزيل %s تعذر (محاولة %d): %s' % (vid, attempt + 1, p.stderr.strip()[-200:])); time.sleep(5 * (attempt + 1))
            else:
                state[vid] = {'error': 'download'}; json.dump(state, open(state_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1); continue
        size = os.path.getsize(local)
        print('%03d %s: رفع %.1f م.ب…' % (i, title[:40], size / 1e6))
        remote = nas.upload(local, folder, name, 'video/mp4' if a.video else 'audio/mp4')
        link = nas.share(remote)
        dur = e.get('duration') or 0
        state[vid] = {'title': title, 'link': link, 'size': size, 'duration': dur, 'ordinal': i}
        json.dump(state, open(state_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        os.remove(local)
        print('     ✓', link)

    # ٤) دمج في shared-content.json ونشره
    if a.no_publish: print('لم يُنشر (--no-publish)'); return
    shared = json.loads(fetch_share(SHARED_URL))
    sheekhs = shared.setdefault('sheekhs', [])
    so = next((s for s in sheekhs if s.get('name') == a.sheekh), None)
    if not so:
        so = {'name': a.sheekh, 'key': 's-' + hashlib.sha1(a.sheekh.encode()).hexdigest()[:12], 'series': []}; sheekhs.append(so)
    bo = next((b for b in so.setdefault('series', []) if b.get('name') == a.series), None)
    if not bo:
        bo = {'name': a.series, 'type': a.type, 'key': 'b-' + slug, 'tapes': []}; so['series'].append(bo)
    tapes = bo.setdefault('tapes', [])
    n_new = n_upd = 0
    for e in entries:
        vid = e['id']; st = state.get(vid)
        if not st or not st.get('link'): continue
        src = 'https://www.youtube.com/watch?v=' + vid
        tp = next((t for t in tapes if t.get('key') == 't-yt-' + vid or vid in t.get('source_url', '') or vid in t.get('media_url', '')), None)
        if tp is None:
            tp = {'title': st['title'], 'key': 't-yt-' + vid, 'segments': []}; tapes.append(tp); n_new += 1
        else:
            n_upd += 1
        tp.update({'media_url': st['link'], 'kind': 'video' if a.video else 'audio', 'source_url': src, 'size': st['size']})
        if 'segments' not in tp: tp['segments'] = []
    shared['updated'] = int(time.time() * 1000); shared['published_by'] = 'yt2nas:' + a.admin; shared['pack_version'] = int(time.time())
    txt = json.dumps(shared, ensure_ascii=False, indent=2)
    tmp = os.path.join(a.workdir, 'shared-content.json'); open(tmp, 'w', encoding='utf-8').write(txt)
    nas.upload(tmp, ROOT, 'shared-content.json', 'application/json')
    print('نُشر: %d درس جديد، %d محدَّث — يصل إلى كل المستخدمين عند فتح التطبيق' % (n_new, n_upd))


if __name__ == '__main__':
    main()
