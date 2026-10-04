"""Bounded HTTPS and durable paid-job journal. No implicit generation retries."""
import base64
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import ssl
import time
import urllib.error
import urllib.request

from PIL import Image

API = 'https://api.pixellab.ai/v2/'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    with temp.open('w') as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.flush()
        os.fsync(file.fileno())
    temp.replace(path)


def secret(name, env_file=None):
    if os.environ.get(name):
        return os.environ[name]
    if env_file:
        for line in Path(env_file).read_text().splitlines():
            if '=' not in line or line.lstrip().startswith('#'):
                continue
            key, value = line.removeprefix('export ').split('=', 1)
            if key.strip() == name:
                return shlex.split(value)[0]
    raise ValueError(name + ' is required')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Unexpected redirect refused')


def download(url, context, limit=10 * 1024 * 1024):
    # No automatic redirect can forward a token or bypass the caller's host check.
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))
    with opener.open(url, timeout=180) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Response exceeds size limit')
    return data


def image_argument(path):
    return {'type': 'base64', 'base64': base64.b64encode(Path(path).read_bytes()).decode()}


def native_image(data):
    with Image.open(io.BytesIO(data)) as original:
        if original.size != (32, 32):
            raise ValueError('Expected native 32x32; automatic resizing is forbidden')
        image = original.convert('RGBA')
    if not image.getchannel('A').getbbox() or image.getchannel('A').getextrema()[0] == 255:
        raise ValueError('Expected nonempty sprite on transparent background')
    return image


class Client:
    def __init__(self, root, key, allow_paid=False, ca_file=None):
        self.root = Path(root)
        self.raw = self.root / 'raw'
        self.raw.mkdir(parents=True, exist_ok=True)
        self.key = key
        self.allow_paid = allow_paid
        self.context = ssl.create_default_context(cafile=ca_file)

    def request(self, method, endpoint, body=None):
        if not re.fullmatch(r'[a-zA-Z0-9_/-]+', endpoint):
            raise ValueError('Invalid provider endpoint')
        request = urllib.request.Request(API + endpoint, method=method,
            headers={'Authorization': 'Bearer ' + self.key, 'Content-Type': 'application/json'},
            data=json.dumps(body).encode() if body is not None else None)
        # Retry reads only; a lost POST response may already be billed.
        for attempt in range(4 if method == 'GET' else 1):
            try:
                return json.loads(download(request, self.context, 16 * 1024 * 1024))
            except urllib.error.HTTPError as error:
                if method == 'POST' or error.code < 500 or attempt == 3:
                    raise RuntimeError(f'PixelLab HTTP {error.code}; request journal retained') from None
            except (OSError, TimeoutError):
                if method == 'POST' or attempt == 3:
                    raise RuntimeError('Provider connection failed; request journal retained') from None
            time.sleep(2 ** attempt)

    def generate(self, label, endpoint, body):
        if not re.fullmatch(r'[a-z0-9_-]+', label):
            raise ValueError('Invalid job label')
        with (self.raw / (label + '.lock')).open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            return self._generate(label, endpoint, body)

    def _generate(self, label, endpoint, body):
        state_path = self.raw / (label + '-state.json')
        result_path = self.raw / (label + '-result.json')
        request_digest = hashlib.sha256(json.dumps({'endpoint': endpoint, 'body': body}, sort_keys=True).encode()).hexdigest()
        if state_path.exists():
            state = read(state_path)
            if state['requestSha256'] != request_digest:
                raise ValueError('Request changed; use a new run directory after review')
            if state['status'] == 'COMPLETED' and result_path.exists():
                return read(result_path)
            if state['status'] == 'FAILED':
                raise ValueError('Failed paid job requires explicit review, not resubmission')
            # Recover a durable acknowledgement after a crash before the state update.
            ack_path = self.raw / (label + '-ack.json')
            if not state.get('jobId') and ack_path.exists():
                state['jobId'] = read(ack_path).get('background_job_id')
                write(state_path, state)
            if not state.get('jobId'):
                raise ValueError('Paid request outcome unknown; never automatically resubmit')
        else:
            if not self.allow_paid:
                raise ValueError('--allow-paid-calls is required')
            state = {'label': label, 'endpoint': endpoint, 'requestSha256': request_digest,
                     'status': 'SUBMITTING', 'submittedAt': time.time()}
            write(self.raw / (label + '-request.json'), body)
            write(state_path, state)
            ack = self.request('POST', endpoint, body)
            write(self.raw / (label + '-ack.json'), ack)
            state.update(jobId=ack.get('background_job_id'), usage=ack.get('usage'), status='ACCEPTED')
            write(state_path, state)
            if not state['jobId']:
                raise ValueError('Missing job ID; inspect acknowledgement, do not resubmit')
            print(label, 'accepted', json.dumps(state['usage']), flush=True)
        deadline = time.monotonic() + 1800
        last_log = 0
        while time.monotonic() < deadline:
            result = self.request('GET', 'background-jobs/' + state['jobId'])
            status = result.get('status')
            if status in ('completed', 'failed'):
                write(result_path, result)
                state.update(status=status.upper(), completedAt=time.time(),
                    usage=result.get('usage') or (result.get('last_response') or {}).get('billing_usage') or state.get('usage'))
                write(state_path, state)
                if status == 'failed':
                    raise ValueError(label + ' failed; inspect retained result')
                print(label, 'completed', flush=True)
                return result
            if time.monotonic() - last_log > 30:
                print(label, 'processing', flush=True)
                last_log = time.monotonic()
            time.sleep(5)
        raise TimeoutError('Polling timed out; rerun to resume the same job')
