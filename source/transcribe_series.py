#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""تفريغ دروس المشرفين (المحتوى المشترك) بالذكاء الاصطناعي من الصوت نفسه وفهرستها مواضعَ بالأزمنة — من حاسوب/خادم، لا من الهاتف.

المسار: shared-content.json ← لكل درس بلا فهرس: تنزيل وسائطه من خادم البيانات ← تقسيمه أجزاءً (ffmpeg، ٣٠ دقيقة، mp3 أحادي ٤٨k)
        ← رفع كل جزء إلى Gemini (Files API) ← تفريغ + مواضيع (JSON) ← دمج الأزمنة ← كتابة segments في الحزمة ← نشرها للجميع.

يلزم: python3، ffmpeg/ffprobe، مفتاح Gemini (من admins.json بالرقم السري للمشرف العام أو --gemini-key).
الاستعمال:
  SUPER_PIN=… python3 transcribe_series.py --series "شرح بلوغ المرام"      # سلسلة بعينها (جزء من الاسم يكفي)
  SUPER_PIN=… python3 transcribe_series.py --all                          # كل الدروس التي بلا فهرس
  الأسرار من متغيرات البيئة (SUPER_PIN أو NAS_PASSWORD + GEMINI_KEY) حتى لا تظهر في ps ولا في سجل الأوامر.
  خيارات: --replace (استبدال الفهارس الموجودة)  --workers 2  --model gemini-3.6-flash  --no-publish  --limit N
الحالة في transcribe_state.json فيُستأنف من حيث توقف. يتوقف رشيقًا بـ SIGTERM/SIGINT بعد إتمام الجاري.
"""
import argparse, base64, http.client, http.cookiejar, json, os, re, signal, ssl, subprocess, sys, threading, time, urllib.parse, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
API_HOST = 'files.murabbie.org'
ROOT = '/downloads/ahl-alhadeeth'
ADMINS_URL = 'https://files.murabbie.org/fsdownload/jbktInN4f/admins.json'
SHARED_URL = 'https://files.murabbie.org/fsdownload/4aPZIQpaW/shared-content.json'
GEMINI = 'https://generativelanguage.googleapis.com'
UA = 'AhlAlhadeeth-transcribe/1.0'
WINDOW_S = 30 * 60
# النماذج بالترتيب: يُجرَّب التالي عند 404 (النماذج تتبدل مع الزمن: 2.5 لم تعد متاحة للمستخدمين الجدد منذ ٢٠٢٦)
FALLBACK_MODELS = ['gemini-3.5-flash', 'gemini-3-flash-preview', 'gemini-3.6-flash', 'gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-flash-latest', 'gemini-2.5-flash']
CA = os.environ.get('SSL_CERT_FILE')
_ctx = ssl.create_default_context(cafile=CA) if CA else ssl.create_default_context()
_proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or '')
STOP = threading.Event()
log_lock = threading.Lock()


def log(*a):
    with log_lock: print(time.strftime('%H:%M:%S'), *a, flush=True)


def fmt_clock(s):
    s = int(s); return '%d:%02d:%02d' % (s // 3600, (s % 3600) // 60, s % 60)


def parse_clock(t):
    t = str(t).strip().translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789'))
    if re.fullmatch(r'\d+(\.\d+)?', t): return float(t)
    parts = t.split(':')
    if not (1 <= len(parts) <= 3) or not all(re.fullmatch(r'\d+(\.\d+)?', p) for p in parts): return None
    total = 0.0
    for p in parts: total = total * 60 + float(p)
    return total


# ---------- اتصال واحد دائم بالخادم (الجلسات مرتبطة بعنوان IP) ----------
class Conn:
    def __init__(self, host=API_HOST):
        self.host, self.c, self.cookies = host, None, {}
    def connect(self):
        if self.c:
            try: self.c.close()
            except Exception: pass
        if _proxy.hostname:
            self.c = http.client.HTTPSConnection(_proxy.hostname, _proxy.port, timeout=600, context=_ctx); self.c.set_tunnel(self.host, 443)
        else:
            self.c = http.client.HTTPSConnection(self.host, 443, timeout=600, context=_ctx)
    def request(self, method, path, body=None, headers=None):
        if not self.c: self.connect()
        h = {'User-Agent': UA, 'Connection': 'keep-alive'}
        if self.cookies: h['Cookie'] = '; '.join('%s=%s' % kv for kv in self.cookies.items())
        if headers: h.update(headers)
        try:
            self.c.request(method, path, body=body, headers=h)
            r = self.c.getresponse()
        except Exception:
            self.connect(); raise
        for k, v in r.getheaders():
            if k.lower() == 'set-cookie':
                kv = v.split(';', 1)[0]
                if '=' in kv: n, val = kv.split('=', 1); self.cookies[n] = val
        return r


def fetch_share_text(url):
    """جلب ملف عبر رابط مشاركة (تهيئة الجلسة على الاتصال نفسه)"""
    m = re.match(r'^https?://([^/]+)/fsdownload/([^/]+)/(.*)$', url)
    last = None
    for i in range(8):
        try:
            c = Conn(m.group(1))
            c.request('GET', '/sharing/' + m.group(2)).read()
            r = c.request('GET', '/fsdownload/%s/%s' % (m.group(2), m.group(3)))
            data = r.read()
            if r.status == 200 and not (r.getheader('Content-Type') or '').startswith('text/html'): return data.decode('utf-8')
            last = 'HTTP %s' % r.status
        except Exception as e:
            last = e
        time.sleep(2 + i)
    raise SystemExit('تعذر جلب %s: %s' % (url, last))


def download_media(url, dest):
    """تنزيل وسائط درس: رابط مشاركة ملف (fsdownload) أو مشاركة مجلد (_sharing_id) أو رابط مباشر"""
    if os.path.exists(dest) and os.path.getsize(dest) > 10000: return dest
    u = urllib.parse.urlparse(url)
    m = re.match(r'^/fsdownload/([^/]+)/', u.path)
    sid = m.group(1) if m else (urllib.parse.parse_qs(u.query).get('_sharing_id') or [None])[0]
    last = None
    for i in range(6):
        try:
            c = Conn(u.hostname)
            if sid: c.request('GET', '/sharing/' + sid).read()
            r = c.request('GET', u.path + ('?' + u.query if u.query else ''))
            ct = r.getheader('Content-Type') or ''
            if r.status != 200 or ct.startswith('text/html') or ct.startswith('application/json'):
                r.read(); last = 'HTTP %s %s' % (r.status, ct); time.sleep(2 + i); continue
            tmp = dest + '.part'
            with open(tmp, 'wb') as f:
                while True:
                    chunk = r.read(1024 * 1024)
                    if not chunk: break
                    f.write(chunk)
            os.replace(tmp, dest)
            return dest
        except Exception as e:
            last = e; time.sleep(2 + i)
    raise RuntimeError('تعذر تنزيل الوسائط: %s' % last)


# ---------- DSM: رفع الحزمة ----------
class Nas:
    def __init__(self, user, pw):
        self.user, self.pw, self.c, self.sid = user, pw, None, None
    def _req(self, method, path, body=None, headers=None):
        if not self.c: self.c = Conn()
        r = self.c.request(method, path, body=body, headers=headers)
        return json.loads(r.read())
    def login(self):
        d = self._req('GET', '/webapi/auth.cgi?' + urllib.parse.urlencode({'api': 'SYNO.API.Auth', 'version': 3, 'method': 'login', 'account': self.user, 'passwd': self.pw, 'session': 'FileStation', 'format': 'sid'}))
        if not d.get('success'): raise RuntimeError('دخول الخادم فشل: %s' % d)
        self.sid = d['data']['sid']
    def upload(self, data, folder, name, mime='application/json'):
        for attempt in range(6):
            try:
                if not self.sid: self.login()
                b = '----AhlAlhadeeth' + uuid.uuid4().hex
                body = b''
                for k, v in [('path', folder), ('create_parents', 'true'), ('overwrite', 'true')]:
                    body += ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (b, k, v)).encode()
                body += ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n' % (b, name, mime)).encode() + data + ('\r\n--%s--\r\n' % b).encode()
                d = self._req('POST', '/webapi/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=' + self.sid, body=body, headers={'Content-Type': 'multipart/form-data; boundary=' + b, 'Content-Length': str(len(body))})
                if d.get('success'): return True
                if d.get('error', {}).get('code') in (119, 105, 106, 107): self.sid = None; continue
                raise RuntimeError('خطأ رفع %s' % d)
            except RuntimeError:
                raise
            except Exception:
                self.c = None; time.sleep(3 * (attempt + 1))
        raise RuntimeError('تعذر رفع ' + name)


# ---------- Gemini ----------
class Gemini:
    """عميل Gemini مع تدوير النماذج: الحصة اليومية المجانية لكل نموذج على حدة (٢٠ طلبًا/يوم للنموذج في الطبقة المجانية)،
    فعند نفاد حصة نموذج يُحجب حتى موعد التصفير (٠٧:٠٠ UTC) ويُستعمل التالي؛ و503 (ضغط) يحجب دقيقتين؛ و404 يحجب نهائيًا."""
    SCHEMA = {'type': 'OBJECT', 'properties': {'topics': {'type': 'ARRAY', 'items': {'type': 'OBJECT', 'properties': {
        'start': {'type': 'STRING'}, 'title': {'type': 'STRING'}, 'question': {'type': 'BOOLEAN'}, 'hadith': {'type': 'INTEGER'}, 'text': {'type': 'STRING'}},
        'required': ['start', 'title', 'question', 'hadith', 'text']}}}, 'required': ['topics']}

    BLOCK_FILE = os.path.join(HERE, 'gemini_blocked.json')

    def __init__(self, key, model):
        # عدة مفاتيح (مشاريع مختلفة) مفصولة بفواصل: لكل مشروع حصته المجانية على حدة
        self.keys = [k.strip() for k in str(key).split(',') if k.strip()]
        self.key = self.keys[0]
        self.models = [model] + [m for m in FALLBACK_MODELS if m != model]
        self.blocked = {}
        try: self.blocked = {k: float(v) for k, v in json.load(open(self.BLOCK_FILE)).items()}
        except Exception: pass
        self.lock = threading.Lock()

    def _http(self, method, url, body=None, headers=None, timeout=600):
        req = urllib.request.Request(url, data=body, method=method, headers={'User-Agent': UA, **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def upload(self, path, mime, name, ki=0):
        key = self.keys[ki]
        size = os.path.getsize(path)
        st, hd, body = self._http('POST', GEMINI + '/upload/v1beta/files?key=' + key, json.dumps({'file': {'display_name': name}}).encode(),
                                  {'X-Goog-Upload-Protocol': 'resumable', 'X-Goog-Upload-Command': 'start', 'X-Goog-Upload-Header-Content-Length': str(size), 'X-Goog-Upload-Header-Content-Type': mime, 'Content-Type': 'application/json'})
        if st != 200: raise RuntimeError('Files API start %s: %s' % (st, body[:200]))
        up = {k.lower(): v for k, v in hd.items()}.get('x-goog-upload-url')
        if not up: raise RuntimeError('لا رابط رفع')
        st, hd, body = self._http('POST', up, open(path, 'rb').read(), {'X-Goog-Upload-Offset': '0', 'X-Goog-Upload-Command': 'upload, finalize', 'Content-Length': str(size)})
        if st != 200: raise RuntimeError('Files API upload %s: %s' % (st, body[:200]))
        f = json.loads(body)['file']
        uri, fname = f['uri'], f['name']
        for i in range(120):
            if f.get('state') == 'ACTIVE': return uri, fname
            time.sleep(3)
            st, hd, body = self._http('GET', GEMINI + '/v1beta/' + fname + '?key=' + key)
            if st == 200: f = json.loads(body)
        raise RuntimeError('الملف لم يصبح ACTIVE')

    def delete(self, fname, ki=0):
        try: self._http('DELETE', GEMINI + '/v1beta/' + fname + '?key=' + self.keys[ki])
        except Exception: pass

    @staticmethod
    def next_reset():
        # حصص الطبقة المجانية تُصفَّر منتصف الليل بتوقيت المحيط الهادئ (٠٧:٠٠ UTC تقريبًا)
        now = time.time(); day = 86400
        t = (int(now) // day) * day + 7 * 3600
        return t if t > now + 60 else t + day

    def pick(self, only_ki=None):
        """(مفتاح، نموذج) غير محجوبين: النماذج بالترتيب ثم المفاتيح (أو ضمن مفتاح بعينه)"""
        with self.lock:
            now = time.time()
            for m in self.models:
                for ki, k in enumerate(self.keys):
                    if only_ki is not None and ki != only_ki: continue
                    if self.blocked.get('%d|%s' % (ki, m), 0) <= now: return ki, m
            return None, None

    def block(self, ki, model, until):
        with self.lock:
            key = '%d|%s' % (ki, model)
            self.blocked[key] = max(self.blocked.get(key, 0), until)
            try: json.dump(self.blocked, open(self.BLOCK_FILE, 'w'))
            except Exception: pass

    def generate(self, parts, temperature=0.1, only_ki=None):
        body = json.dumps({'contents': [{'parts': parts}], 'generationConfig': {'temperature': temperature, 'response_mime_type': 'application/json', 'response_schema': self.SCHEMA, 'max_output_tokens': 65536}}).encode()
        last = None
        for attempt in range(12):
            ki, model = self.pick(only_ki)
            if model is None:
                soonest = min(self.blocked.values()) if self.blocked else time.time() + 60
                raise QuotaExhausted('نفدت حصص كل النماذج حتى %s UTC' % time.strftime('%H:%M', time.gmtime(soonest)))
            t0 = time.time()
            st, hd, raw = self._http('POST', GEMINI + '/v1beta/models/%s:generateContent?key=%s' % (model, self.keys[ki]), body, {'Content-Type': 'application/json'}, timeout=900)
            if st != 200: log('  %s → HTTP %s بعد %.0f ث' % (model, st, time.time() - t0))
            if st == 200:
                d = json.loads(raw)
                try:
                    text = ''.join(p.get('text', '') for p in d['candidates'][0]['content']['parts'])
                    if text.strip():
                        log('  %s ✓ %.0f ث، %d حرف' % (model, time.time() - t0, len(text))); return text, model
                    last = 'رد فارغ: %s' % raw[:200]
                except Exception:
                    last = 'رد بلا نص: %s' % raw[:200]
                time.sleep(5); continue
            msg = raw.decode('utf-8', 'replace')
            if st == 404: self.block(ki, model, time.time() + 10 * 86400); continue
            if st == 503: self.block(ki, model, time.time() + 120); last = '503 ' + model; continue
            if st == 429:
                if 'PerDay' in msg or 'per day' in msg.lower() or 'free_tier_requests' in msg:
                    self.block(ki, model, self.next_reset()); log('  نفدت حصة %s اليومية (مفتاح %d)' % (model, ki + 1)); continue
                m = re.search(r'retry in ([0-9.]+)s', msg)
                wait = min(120, float(m.group(1)) + 2) if m else 30
                last = '429 ' + model; time.sleep(wait); continue
            if st in (500, 502, 504): last = 'HTTP %s' % st; time.sleep(15 * (attempt + 1)); continue
            raise RuntimeError('Gemini %s: %s' % (st, msg[:300]))
        raise RuntimeError('Gemini: %s' % last)


class QuotaExhausted(Exception):
    pass


def prompt_for(title, a, b, first_only):
    scope = 'فرّغه كاملًا من أوله إلى آخره' if first_only else 'هذا جزء من الدرس يبدأ عند %s وينتهي عند %s من التسجيل الأصلي؛ فرّغه كاملًا' % (fmt_clock(a), fmt_clock(b))
    first = fmt_clock(a)
    return ('استمع إلى هذا الدرس العلمي الشرعي بعنوان «%s» و%s بالعربية الفصحى مع تصحيح الإملاء وإثبات الآيات والأحاديث كما نُطقت، '
            'ثم قسّم ما فرّغته إلى مواضع متتابعة بحسب المعنى كما تُفهرس أشرطة الدروس: كل مسألة أو باب أو حديث مشروح أو سؤال مع جوابه أو فائدة مستقلة موضعٌ. أعطِ لكل موضع:\n'
            '- "start": زمن بدايته الدقيق بصيغة h:mm:ss محسوبًا من بداية هذا الملف الصوتي.\n'
            '- "title": عنوانًا مختصرًا واضحًا (٤–١٤ كلمة) على طريقة فهارس الأشرطة (مثل: «شرح حديث: … — حكم كذا»)؛ وإن كان سؤالًا فنص السؤال منتهيًا بعلامة استفهام.\n'
            '- "question": true إن كان سؤالًا وجوابه وإلا false.\n'
            '- "hadith": رقم الحديث المشروح إن ذُكر رقمه صراحةً وإلا 0.\n'
            '- "text": التفريغ الكامل لكلام الشيخ في هذا الموضع بلا اختصار ولا تلخيص، بترقيم مناسب.\n'
            'لا تجعل المواضع أقصر من ٣٠ ثانية غالبًا ولا أطول من عشر دقائق، وابدأ أول موضع من 0:00:00. أخرج JSON فقط: {"topics":[{"start":"0:00:00","title":"…","question":false,"hadith":0,"text":"…"}]}') % (title, scope)


def parse_topics(text):
    t = text.strip()
    if t.startswith('```'): t = t.strip('`'); t = t[t.find('{'):] if '{' in t else t
    try:
        o = json.loads(t)
    except Exception:
        o = json.loads(t[t.find('{'):t.rfind('}') + 1])
    arr = o if isinstance(o, list) else (o.get('topics') or o.get('segments') or o.get('items') or [])
    out = []
    for g in arr:
        if not isinstance(g, dict): continue
        s = parse_clock(g.get('start', g.get('start_time', g.get('time', ''))))
        title = (g.get('title') or g.get('line') or '').strip()
        if s is None or not title: continue
        out.append({'start': s, 'line': title, 'write': (g.get('text') or '').strip() or None, 'ques': bool(g.get('question') or g.get('ques')), 'hnum': int(g.get('hadith') or g.get('hnum') or 0)})
    out.sort(key=lambda x: x['start'])
    return out


def duration_of(path):
    p = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', path], capture_output=True, text=True)
    try: return float(p.stdout.strip())
    except Exception: return 0.0


def make_chunks(src, workdir, base, dur):
    """أجزاء ٣٠ دقيقة mp3 أحادي ٤٨k (أصغر رفعًا؛ الجودة كافية للتفريغ)"""
    # أجزاء متساوية لا يقل الجزء عن ٢٠ دقيقة ولا يزيد على ٣٦ (لا يُترك ذيل قصير يُفهرس على حدة)
    n = max(1, round(dur / WINDOW_S)) if dur > WINDOW_S + 300 else 1
    part = dur / n if n > 1 else 0
    out = []
    for i in range(n):
        p = os.path.join(workdir, '%s-%02d.mp3' % (base, i))
        start = i * part
        expected = part if n > 1 else dur
        if os.path.exists(p) and abs(duration_of(p) - expected) > 5: os.remove(p)  # جزء ناقص من تشغيل سابق أُوقف
        if not os.path.exists(p) or os.path.getsize(p) < 1000:
            cmd = ['ffmpeg', '-v', 'error', '-y', '-i', src]
            if n > 1: cmd += ['-ss', '%.3f' % start, '-t', '%.3f' % (part + 0.5)]
            cmd += ['-vn', '-ac', '1', '-ar', '22050', '-b:a', '48k', p]
            subprocess.run(cmd, check=True, capture_output=True)
        out.append((start, p))
    return out


def transcribe_tape(gem, tape, workdir, sleep_s):
    title = tape.get('title') or 'درس'
    key = tape.get('key') or re.sub(r'[^A-Za-z0-9]', '', tape.get('media_url', ''))[-12:]
    base = re.sub(r'[^A-Za-z0-9_-]', '', key) or 'tape'
    media = os.path.join(workdir, base + '.media')
    download_media(tape['media_url'], media)
    dur = duration_of(media)
    if dur <= 0:
        os.remove(media); download_media(tape['media_url'], media); dur = duration_of(media)
        if dur <= 0: raise RuntimeError('ملف الوسائط غير صالح')
    try:
        chunks = make_chunks(media, workdir, base, dur)
    except subprocess.CalledProcessError as e:
        if STOP.is_set(): raise KeyboardInterrupt
        raise RuntimeError('ffmpeg: %s' % (e.stderr or b'')[-200:].decode('utf-8', 'replace'))
    segs = []
    for i, (offset, path) in enumerate(chunks):
        if STOP.is_set(): raise KeyboardInterrupt
        cache = os.path.join(workdir, '%s-%02d.json' % (base, i))  # نتيجة الجزء تُحفظ فيُستأنف الدرس من جزئه
        if os.path.exists(cache):
            topics = json.load(open(cache, encoding='utf-8'))
        else:
            part_len = (chunks[i + 1][0] - offset) if i + 1 < len(chunks) else max(0.0, (dur or 0) - offset)
            topics = []
            tried_keys = set()
            while not topics:
                ki, model = gem.pick()
                if model is None or ki in tried_keys: raise QuotaExhausted('نفدت حصص كل النماذج والمفاتيح')
                tried_keys.add(ki)
                uri, fname = gem.upload(path, 'audio/mpeg', '%s-%d' % (base, i), ki)  # الملف المرفوع خاص بمشروع المفتاح
                try:
                    for attempt in range(3):
                        try:
                            text, used_model = gem.generate([{'text': prompt_for(title, offset, offset + part_len, len(chunks) == 1)}, {'file_data': {'mime_type': 'audio/mpeg', 'file_uri': uri}}], only_ki=ki)
                        except QuotaExhausted:
                            break  # هذا المفتاح نفد: مفتاح آخر (رفع جديد)
                        try:
                            topics = parse_topics(text)
                        except Exception as e:
                            log('  رد غير صالح للجزء %d (%s) — إعادة' % (i + 1, str(e)[:60])); topics = []
                        if topics: break
                finally:
                    gem.delete(fname, ki)
            if not topics: raise RuntimeError('لا مواضع في رد Gemini للجزء %d' % (i + 1))
            json.dump(topics, open(cache + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(cache + '.tmp', cache)  # ذرّية: ملف مبتور يُفشل الدرس إلى الأبد
        last_end = segs[-1]['start'] if segs else -1
        for t in topics:
            s = t['start'] + offset
            if s <= last_end: continue
            if len(chunks) > 1 and i + 1 < len(chunks) and s >= chunks[i + 1][0] + 60: continue
            t['start'] = s; segs.append(t)
        time.sleep(sleep_s)
    for p in [media] + [p for _, p in chunks] + [os.path.join(workdir, '%s-%02d.json' % (base, i)) for i in range(len(chunks))]:
        try: os.remove(p)
        except OSError: pass
    return segs, dur


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--super-pin', dest='super_pin', default=os.environ.get('SUPER_PIN')); ap.add_argument('--admin', default='admin')
    ap.add_argument('--gemini-key', dest='gemini_key', default=os.environ.get('GEMINI_KEY')); ap.add_argument('--user'); ap.add_argument('--password', default=os.environ.get('NAS_PASSWORD'))
    ap.add_argument('--series', default=''); ap.add_argument('--sheekh', default=''); ap.add_argument('--all', action='store_true')
    ap.add_argument('--replace', action='store_true'); ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--workers', type=int, default=2); ap.add_argument('--model', default='gemini-3.5-flash'); ap.add_argument('--sleep', type=float, default=4)
    ap.add_argument('--workdir', default=os.path.join(HERE, 'transcribe-work')); ap.add_argument('--state', default=os.path.join(HERE, 'transcribe_state.json'))
    ap.add_argument('--no-publish', action='store_true')
    ap.add_argument('--max-seconds', type=float, default=0, help='لا تبدأ درسًا جديدًا بعد هذه المدة (للتشغيل على دفعات)')
    a = ap.parse_args()
    if a.max_seconds > 0: threading.Timer(a.max_seconds, STOP.set).start()
    os.makedirs(a.workdir, exist_ok=True)

    # المفاتيح
    user, pw, gkey = a.user, a.password if a.user else None, a.gemini_key  # كلمة سر البيئة تخص --user المصرَّح به فقط
    if not (user and pw) or not gkey:
        if not a.super_pin: raise SystemExit('يلزم --super-pin (أو --user/--password مع --gemini-key)')
        sys.path.insert(0, HERE); import admins_tool as at
        reg = json.loads(fetch_share_text(ADMINS_URL))
        entry = at.find(reg, a.admin)
        if not entry: raise SystemExit('لا يوجد مشرف ' + a.admin)
        k = at.derive(a.super_pin, base64.b64decode(entry['salt']), at.iters(reg))
        if at.verifier(k) != entry['hash']: raise SystemExit('الرقم السري غير صحيح')
        lines = at.unwrap(k, entry['wrapped']).split('\n')
        user, pw = user or lines[0], pw or lines[1]
        gkey = gkey or (lines[2].strip() if len(lines) > 2 else '')
    if not gkey: raise SystemExit('لا مفتاح Gemini: يضعه المشرف العام في التطبيق («المشرفون ← مفاتيح الخادم والذكاء الاصطناعي») أو مرّره بـ --gemini-key')
    gem = Gemini(gkey, a.model)

    shared = json.loads(fetch_share_text(SHARED_URL))
    todo = []
    for so in shared.get('sheekhs', []):
        if a.sheekh and a.sheekh not in so.get('name', ''): continue
        for bo in so.get('series', []):
            if a.series and a.series not in bo.get('name', ''): continue
            if not (a.series or a.sheekh or a.all): continue
            for tp in bo.get('tapes', []):
                if not (tp.get('media_url') or '').startswith('http'): continue
                if tp.get('segments') and not a.replace: continue
                todo.append((so, bo, tp))
    if a.limit: todo = todo[:a.limit]
    if not todo: raise SystemExit('لا دروس مرشحة (حدد --series أو --all، أو --replace)')
    state = json.load(open(a.state, encoding='utf-8')) if os.path.exists(a.state) else {}
    lock = threading.Lock()
    signal.signal(signal.SIGTERM, lambda *_: STOP.set()); signal.signal(signal.SIGINT, lambda *_: STOP.set())
    log('دروس مرشحة: %d — النموذج %s' % (len(todo), a.model))

    def save():
        with lock: json.dump(state, open(a.state + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(a.state + '.tmp', a.state)

    def tid(tp): return tp.get('key') or tp.get('media_url')

    def work(item):
        so, bo, tp = item
        if STOP.is_set(): return
        k = tid(tp)
        if state.get(k, {}).get('segments') and not a.replace: return
        t0 = time.time()
        try:
            segs, dur = transcribe_tape(gem, tp, a.workdir, a.sleep)
            with lock: state[k] = {'segments': segs, 'duration': dur, 'title': tp.get('title'), 'done': time.strftime('%Y-%m-%d %H:%M')}
            save()
            log('✓ %s — %d موضعًا — %.0f دقيقة صوت في %.0f ث' % ((tp.get('title') or '')[:40], len(segs), dur / 60, time.time() - t0))
        except KeyboardInterrupt:
            return
        except QuotaExhausted as e:
            if not STOP.is_set(): log('⏸ %s' % e)
            STOP.set(); return
        except Exception as e:
            with lock: state[k] = {**state.get(k, {}), 'error': str(e)[:300]}
            save(); log('✗ %s — %s' % ((tp.get('title') or '')[:40], str(e)[:200]))

    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        list(ex.map(work, todo))

    # كتابة النتائج في الحزمة ونشرها
    n = 0
    for so, bo, tp in todo:
        st = state.get(tid(tp), {})
        if not st.get('segments'): continue
        tp['segments'] = [{'start': fmt_clock(s['start']), 'start_ms': int(s['start'] * 1000), 'line': s['line'], **({'write': s['write']} if s.get('write') else {}), **({'ques': True} if s.get('ques') else {}), **({'hnum': s['hnum']} if s.get('hnum') else {})} for s in st['segments']]
        n += 1
    done_total = sum(1 for v in state.values() if v.get('segments'))
    log('%s: %d درس مفهرس في هذه الدفعة، %d في السجل — بقي %d' % ('أُوقف مؤقتًا' if STOP.is_set() else 'انتهى', n, done_total, sum(1 for _, _, tp in todo if not state.get(tid(tp), {}).get('segments'))))
    if a.no_publish or n == 0: return
    # أحدث نسخة من الحزمة (قد يكون مشرف نشر تغييرات أثناء العمل) ثم دمج الفهارس بمفاتيح الدروس
    latest = json.loads(fetch_share_text(SHARED_URL))
    by_key = {}
    for so, bo, tp in todo:
        if state.get(tid(tp), {}).get('segments') and tp.get('key'): by_key[tp['key']] = tp['segments']
    if latest.get('format') != 'ahl-alhadeeth-pack': raise SystemExit('shared-content.json المجلوب ليس حزمة صالحة — لم يُنشر شيء')
    applied = 0
    for so in latest.get('sheekhs', []):
        for bo in so.get('series', []):
            for tp in bo.get('tapes', []):
                if tp.get('key') in by_key: tp['segments'] = by_key[tp['key']]; applied += 1
    if applied == 0: log('لا درس مطابق في أحدث نسخة — لم يُنشر شيء'); return
    latest['updated'] = int(time.time() * 1000); latest['published_by'] = 'transcribe:' + a.admin; latest['pack_version'] = int(time.time())
    Nas(user, pw).upload(json.dumps(latest, ensure_ascii=False, indent=1).encode('utf-8'), ROOT, 'shared-content.json')
    log('نُشر للجميع: %d درس بفهارسه وتفريغه' % applied)


if __name__ == '__main__':
    main()
