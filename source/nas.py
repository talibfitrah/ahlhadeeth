#!/usr/bin/env python3
"""أدوات الرفع إلى NAS عبر واجهة DSM FileStation (files.murabbie.org:443)."""
import json, os, sys, time, subprocess, urllib.parse, urllib.request

BASE = 'https://files.murabbie.org/webapi'
ACCOUNT = 'manus'
PASSWD = os.environ.get('NAS_PASSWORD', '')  # كلمة سر حساب manus — من ملف الأسرار: export NAS_PASSWORD='…'
REMOTE_DIR = '/downloads/ahl-alhadeeth'
_sid = None


def curl(args, retries=6):
    for attempt in range(retries):
        p = subprocess.run(['curl', '-sS', '-m', '1800'] + args, capture_output=True, text=True)
        out = p.stdout.strip()
        if p.returncode == 0 and out.startswith('{'):
            try:
                d = json.loads(out)
                if d.get('success'):
                    return d
                print('  api error', d, file=sys.stderr)
            except Exception as e:
                print('  bad json', out[:200], file=sys.stderr)
        else:
            print('  curl rc', p.returncode, p.stderr[:200], out[:200], file=sys.stderr)
        time.sleep(3 * (attempt + 1))
    raise RuntimeError('curl failed: %s' % args[:3])


def sid():
    global _sid
    if _sid:
        return _sid
    d = curl(['-G', BASE + '/auth.cgi', '--data-urlencode', 'api=SYNO.API.Auth', '--data-urlencode', 'version=3',
              '--data-urlencode', 'method=login', '--data-urlencode', 'account=' + ACCOUNT,
              '--data-urlencode', 'passwd=' + PASSWD, '--data-urlencode', 'session=FileStation', '--data-urlencode', 'format=sid'])
    _sid = d['data']['sid']
    return _sid


def api(name, version, method, **params):
    args = ['-G', BASE + '/entry.cgi', '--data-urlencode', 'api=' + name, '--data-urlencode', 'version=%s' % version,
            '--data-urlencode', 'method=' + method, '--data-urlencode', '_sid=' + sid()]
    for k, v in params.items():
        args += ['--data-urlencode', '%s=%s' % (k, v)]
    return curl(args)


def mkdir(path):
    parent, name = path.rsplit('/', 1)
    return api('SYNO.FileStation.CreateFolder', 2, 'create', folder_path=parent, name=name, force_parent='true')


def listdir(path):
    d = api('SYNO.FileStation.List', 2, 'list', folder_path=path, additional='["size"]')
    return {f['name']: f['additional']['size'] for f in d['data']['files']}


def upload(local, remote_dir=REMOTE_DIR, name=None):
    name = name or os.path.basename(local)
    size = os.path.getsize(local)
    for attempt in range(5):
        d = None
        try:
            d = curl(['-X', 'POST', '%s/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid=%s' % (BASE, sid()),
                      '-F', 'path=' + remote_dir, '-F', 'create_parents=true', '-F', 'overwrite=true',
                      '-F', 'file=@%s;filename=%s' % (local, name)], retries=1)
        except Exception as e:
            print('  upload attempt failed', e, file=sys.stderr)
        got = listdir(remote_dir).get(name)
        if got == size:
            print('uploaded', name, size, flush=True)
            return True
        print('  size mismatch for', name, got, '!=', size, '- retrying', flush=True)
        time.sleep(5)
    raise RuntimeError('upload failed ' + name)


def share(path):
    d = api('SYNO.FileStation.Sharing', 3, 'create', path=json.dumps([path]))
    return d['data']['links'][0]


def shares():
    d = api('SYNO.FileStation.Sharing', 3, 'list', offset=0, limit=1000)
    return d['data']['links']


def delete(path):
    return api('SYNO.FileStation.Delete', 2, 'delete', path=json.dumps([path]))


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'mkdir':
        print(mkdir(sys.argv[2]))
    elif cmd == 'ls':
        for k, v in sorted(listdir(sys.argv[2] if len(sys.argv) > 2 else REMOTE_DIR).items()):
            print(v, k)
    elif cmd == 'up':
        for f in sys.argv[2:]:
            upload(f)
    elif cmd == 'share':
        for p in sys.argv[2:]:
            print(json.dumps(share(p), ensure_ascii=False))
    elif cmd == 'shares':
        for l in shares():
            print(l['id'], l['path'], l['url'], l.get('status'))
    elif cmd == 'rm':
        for p in sys.argv[2:]:
            print(delete(p))
