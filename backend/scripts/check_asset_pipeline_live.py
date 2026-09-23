"""Opt-in development E2E: only the deployed server calls AI and renders assets."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPSHandler, HTTPRedirectHandler
import uuid

from run_supabase import BACKEND, ConfigurationError, load_settings, load_storage_settings

# This tool creates temporary fixtures and must never target a production project.
PROJECT_REF = 'gwimdiwrqfcqulefshoz'
PROJECT = f'https://{PROJECT_REF}.supabase.co'
API = 'https://shelter-connect-dev.onrender.com'
SHELTER = '02100000-0000-4000-8000-000000000001'
PLAN = {'BASE', 'IDLE', 'WALK', 'RUN', 'SIT'}
MAX_RESPONSE = 8 * 1024 * 1024


class CheckFailed(Exception):
    """Messages contain check names, never response bodies or credentials."""


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def checked_url(url):
    parsed = urlsplit(url)
    require(parsed.scheme == 'https' and parsed.netloc in
            (urlsplit(PROJECT).netloc, urlsplit(API).netloc), 'Unexpected HTTP destination')
    require(not parsed.username and not parsed.password and not parsed.fragment,
            'Unexpected HTTP URL')
    return url


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def sql_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def check_manifest(manifest, expected_actions):
    require(set(manifest['availableActions']) == expected_actions, 'Manifest action mismatch')
    require(set(manifest['animations']) == expected_actions, 'Missing or unexpected animation')
    require(manifest['frameSize'] == {'width': 64, 'height': 64}, 'Invalid frame size')
    require(manifest['anchorPixels'] == {'x': 32, 'y': 60}, 'Invalid sprite anchor')
    require(manifest['fallbackAction'] == 'IDLE', 'Invalid fallback')
    for action, clip in manifest['animations'].items():
        count, duration = {'IDLE': (16, 140), 'WALK': (24, 60),
                           'RUN': (24, 30), 'SIT': (16, 90)}[action]
        require(clip['frameCount'] == count and len(clip['frames']) == count,
                'Invalid frame count: ' + action)
        for i, frame in enumerate(clip['frames']):
            require(frame == {'x': i * 64, 'y': 0, 'width': 64, 'height': 64,
                              'durationMs': duration}, 'Invalid frame metadata: ' + action)
        require(clip['loop'] == (action != 'SIT'), 'Invalid loop: ' + action)
        require(clip['holdLastFrame'] == (action == 'SIT'), 'Invalid last-frame hold: ' + action)


def check_sheet(png, count):
    from PIL import Image
    with Image.open(io.BytesIO(png)) as original:
        require(original.format == 'PNG' and original.size == (count * 64, 64),
                'Invalid PNG sheet dimensions')
        sheet = original.convert('RGBA')
    hashes = set()
    for i in range(count):
        frame = sheet.crop((i * 64, 0, (i + 1) * 64, 64))
        alpha = frame.getchannel('A')
        require(alpha.getbbox() is not None and alpha.getextrema()[0] == 0,
                'Empty frame or missing transparent background')
        hashes.add(hashlib.sha256(frame.tobytes()).hexdigest())
    require(count == 1 or len(hashes) > 1, 'Animation repeats a single still frame')
    return len(hashes)


def reference_image():
    # Test INPUT only. Every output image must be downloaded from the server.
    from PIL import Image, ImageDraw
    image = Image.new('RGBA', (96, 96), (235, 242, 226, 255))
    draw = ImageDraw.Draw(image)
    dark, fur = '#624c40', '#d7aa70'
    draw.rectangle((22, 34, 64, 62), fill=fur, outline=dark, width=2)
    draw.rectangle((58, 22, 79, 46), fill=fur, outline=dark, width=2)
    draw.polygon([(61, 23), (58, 10), (70, 23)], fill=dark)
    draw.polygon([(74, 23), (80, 11), (79, 35)], fill=dark)
    draw.rectangle((76, 35, 86, 41), fill=fur, outline=dark, width=2)
    draw.rectangle((83, 34, 87, 38), fill=dark)
    draw.rectangle((69, 29, 72, 32), fill=dark)
    for x, y in ((24, 57), (34, 58), (53, 57), (61, 55)):
        draw.rectangle((x, y, x + 5, 78), fill=fur, outline=dark, width=1)
    draw.line([(23, 39), (15, 26), (12, 15)], fill=dark, width=6)
    output = io.BytesIO()
    image.save(output, format='PNG')
    return output.getvalue()


class LiveCheck:
    def __init__(self, args):
        self.args = args
        db = load_settings(args.env_file)
        require(args.project_ref == PROJECT_REF and db['SUPABASE_URL'] == PROJECT,
                'The explicitly selected development project must match the configuration')
        self.secret = load_storage_settings(args.storage_env_file)['SUPABASE_SECRET_KEY']
        database = urlsplit(db['DB_URL'][5:])
        self.pg = dict(os.environ, PGHOST=database.hostname, PGPORT=str(database.port),
                       PGUSER=db['DB_USERNAME'], PGPASSWORD=db['DB_PASSWORD'],
                       PGDATABASE='postgres', PGSSLMODE='require', PGCONNECT_TIMEOUT='15')
        self.psql = args.psql or shutil.which('psql')
        require(bool(self.psql), 'Install psql or provide --psql')
        context = ssl.create_default_context(cafile=args.ca_file)
        self.opener = build_opener(NoRedirect(), HTTPSHandler(context=context))
        self.output = args.output_dir.resolve()
        require(not self.output.is_relative_to(BACKEND.parent), 'Output must be outside the Git checkout')
        require(not self.output.exists(), 'Output directory must be new')
        self.output.mkdir(mode=0o700, parents=True)
        self.run = 'asset-e2e-' + str(uuid.uuid4())
        self.auth_id = self.user_id = self.dog = self.job = self.token = None
        self.permission = str(uuid.uuid4())
        self.member = str(uuid.uuid4())
        self.report = {'run': self.run, 'status': 'STARTED', 'checks': [],
                       'apiOrigin': API, 'paidSubmissionLimitForRun': 3}
        self.tables, self.before = [], {}
        self.save()

    def save(self):
        target = self.output / 'report.json'
        with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as file:
            json.dump(self.report, file, ensure_ascii=False, indent=2)

    def passed(self, message):
        self.report['checks'].append(message)
        self.save()
        print('PASS ' + message, flush=True)

    def request(self, method, url, body=None, headers=None, expected=(200,), raw=False):
        checked_url(url)
        headers = dict(headers or {})
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
            headers['Content-Type'] = 'application/json'
        try:
            response = self.opener.open(Request(url, data=body, headers=headers, method=method), timeout=60)
        except HTTPError as error:
            response = error
        with response:
            data = response.read(MAX_RESPONSE + 1)
            require(len(data) <= MAX_RESPONSE, 'HTTP response exceeded the size limit')
            if response.status not in expected:
                try:
                    code = json.loads(data).get('code', '')
                except (ValueError, AttributeError):
                    code = ''
                code = code if isinstance(code, str) and re.fullmatch('[A-Z0-9_]{1,80}', code) else 'UNSPECIFIED'
                raise CheckFailed(f'HTTP {response.status} {code} {urlsplit(url).path}')
            return data if raw else json.loads(data) if data else None

    def api(self, method, path, body=None, expected=(200,), anonymous=False):
        headers = {} if anonymous else {'Authorization': 'Bearer ' + self.token}
        return self.request(method, API + path, body, headers, expected)

    def auth(self, method, path, body=None, expected=(200,)):
        return self.request(method, PROJECT + '/auth/v1' + path, body, {'apikey': self.secret}, expected)

    def query(self, sql):
        result = subprocess.run([self.psql, '-X', '-v', 'ON_ERROR_STOP=1', '-At'],
                                input=sql, env=self.pg, capture_output=True, text=True, timeout=45)
        require(result.returncode == 0, 'Development DB query failed (details not logged)')
        return result.stdout.strip()

    def snapshot(self):
        # One statement gives a consistent snapshot and avoids one remote connection per table.
        rows = self.query(' UNION ALL '.join(
            "SELECT '" + table + "' || ':' || md5(to_jsonb(t)::text) FROM shelter." + table + ' t'
            for table in self.tables) + ';').splitlines()
        result = {table: set() for table in self.tables}
        for row in rows:
            table, fingerprint = row.split(':', 1)
            result[table].add(fingerprint)
        return result

    def setup(self):
        self.tables = self.query("SELECT tablename FROM pg_tables WHERE schemaname='shelter' "
                                 "AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
        require(len(self.tables) == 19 and all(re.fullmatch('[a-z_]+', t) for t in self.tables),
                'Unexpected development schema')
        self.before = self.snapshot()
        require(self.query("SELECT count(*) FROM shelter.asset_jobs WHERE status IN ('QUEUED','RUNNING');") == '0',
                'Another asset generation is running')
        # Free instances can take longer than one HTTP timeout to wake up. Only this
        # read-only readiness request is retried; paid/mutating requests never are.
        ready = False
        for attempt in range(4):
            try:
                ready = self.request('GET', API + '/actuator/health/readiness') == {'status': 'UP'}
            except (TimeoutError, OSError, CheckFailed):
                pass
            if ready:
                break
            print(f'WAIT development server waking up ({attempt + 1}/4)', flush=True)
            time.sleep(5)
        require(ready, 'Server did not become ready; no fixture or paid request created')
        for bucket in ('dog-photos', 'dog-assets'):
            info = self.request('GET', PROJECT + '/storage/v1/bucket/' + bucket, headers={'apikey': self.secret})
            require(info['public'] is False, 'Storage bucket must be private')
        self.passed('Development target, ready server and private Storage buckets')
        email, password = self.run + '@example.invalid', secrets.token_urlsafe(42) + 'aA9!'
        user = self.auth('POST', '/admin/users', {'email': email, 'password': password,
                         'email_confirm': True, 'app_metadata': {'verification_run': self.run}}, (200, 201))
        self.auth_id = user['id']
        self.report['authUserId'] = self.auth_id
        self.save()
        login = self.auth('POST', '/token?grant_type=password', {'email': email, 'password': password})
        require(login['user']['id'] == self.auth_id, 'Unexpected login identity')
        self.token = login['access_token']
        account = self.api('POST', '/v1/me')['data']
        self.user_id = account['id']
        require(account['role'] == 'USER', 'Fixture must be an ordinary user')
        q = sql_literal
        # Only test membership and permission need DB setup; business inputs use HTTP APIs.
        self.query(f"""BEGIN;
          INSERT INTO shelter.shelter_memberships(id,user_id,shelter_id,role,status)
          VALUES ({q(self.member)},{q(self.user_id)},{q(SHELTER)},'STAFF','ACTIVE');
          INSERT INTO shelter.asset_source_permissions(id,shelter_id,source_key,source_kind,permission_note,
              crawl_allowed,derivatives_allowed,pixellab_allowed,auto_generate,recorded_by)
          VALUES ({q(self.permission)},{q(SHELTER)},{q(self.run)},'SHELTER',
            '직접 그린 E2E 검증용 그림만 사용. 실제 보호소 사진 허가가 아님.',false,true,true,true,{q(self.user_id)});
          COMMIT;""")
        dog = self.api('POST', '/v1/shelter-admin/dogs', {
            'shelterId': SHELTER, 'name': '자동 검증용 가상 강아지', 'avatarKey': 'asset-e2e',
            'isPublic': False, 'introduction': self.run}, (201,))['data']
        self.dog = dog['id']
        self.report.update(dogId=self.dog, permissionId=self.permission, appUserId=self.user_id)
        self.save()
        observation = self.api('POST', self.dog_path + '/observations', {
            'category': 'PLAY', 'content': '가상 검증 기록: 마당에서 빠르게 달리기를 여러 번 반복했다.',
            'observedAt': datetime.now(timezone.utc).isoformat(), 'sourceNote': self.run,
            'status': 'CONFIRMED'}, (201,))['data']
        self.passed('Real login, dog registration and confirmed observation through server APIs')
        return observation['id']

    @property
    def dog_path(self):
        return '/v1/shelter-admin/dogs/' + self.dog

    @property
    def job_path(self):
        return self.dog_path + '/assets/' + self.job

    def behavior(self, observation):
        body = {'clientRequestId': str(uuid.uuid4()), 'expectedRevision': 0,
                'evidenceObservationIds': [observation]}
        suggestion = self.api('POST', self.dog_path + '/behavior/suggestions', body)['data']
        require(suggestion['status'] == 'COMPLETED', 'Behavior analysis did not complete')
        profile = suggestion['result']['profile']
        require(suggestion['result']['requiresConfirmation'] and profile['status'] == 'DRAFT',
                'Behavior draft must require confirmation')
        enabled = {key for key, motion in profile['settings']['actions'].items() if motion['weight'] > 0}
        require(enabled == PLAN - {'BASE'}, 'Behavior did not match the runner observation')
        again = self.api('POST', self.dog_path + '/behavior/suggestions', body)['data']
        require(again['id'] == suggestion['id'], 'Duplicate behavior request created another suggestion')
        confirmed = self.api('POST', self.dog_path + '/behavior/confirmation',
                             {'expectedRevision': profile['revision']})['data']
        require(confirmed['status'] == 'CONFIRMED', 'Behavior was not confirmed')
        self.report['behaviorRevision'] = confirmed['revision']
        self.passed('Actual server AI classification -> RUN selection -> confirmation; request deduplicated')

    def upload(self):
        picture = reference_image()
        (self.output / 'input.png').write_bytes(picture)
        boundary = 'shelter-' + uuid.uuid4().hex
        metadata = {'clientUploadId': str(uuid.uuid4()), 'permissionId': self.permission,
                    'rightsConfirmed': True, 'rightsNote': '직접 그린 자동 검증용 가상 그림. 실제 보호소 사진 아님.'}
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
                   + json.dumps(metadata, ensure_ascii=False).encode()
                   + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic-dog.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
                   + picture + f'\r\n--{boundary}--\r\n'.encode())
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}
        first = self.request('POST', API + self.dog_path + '/photos', payload, headers)['data']
        self.job = first['job']['id']
        self.report.update(assetJobId=self.job, photoId=first['photoId'])
        self.save()
        require(set(first['job']['actionPlan']) == PLAN, 'Unexpected generated action plan')
        require(first['job']['behaviorRevision'] == self.report['behaviorRevision'], 'Wrong behavior revision')
        second = self.request('POST', API + self.dog_path + '/photos', payload, headers)['data']
        require(first['photoId'] == second['photoId'] and self.job == second['job']['id'],
                'Photo replay did not reuse the same photo and job')
        self.passed('Multipart upload -> automatic server job with IDLE/WALK/SIT/RUN; duplicate reused')

    def submissions(self):
        return int(self.query('SELECT count(*) FROM shelter.asset_submissions WHERE job_id=' + sql_literal(self.job) + ';'))

    def wait_for(self, wanted):
        deadline, last = time.monotonic() + self.args.timeout_seconds, None
        while time.monotonic() < deadline:
            job = self.api('GET', self.job_path)['data']
            signature = job['status'] + ' ' + ','.join(step['action'] + ':' + step['status'] for step in job['steps'])
            if signature != last:
                print('WORKER ' + signature, flush=True)
                last = signature
            require(self.submissions() <= 3, 'Unexpected extra paid submission')
            if job['status'] == wanted:
                return job
            require(job['status'] not in ('FAILED', 'OUTCOME_UNKNOWN', 'CANCELLED', 'REJECTED'),
                    'Worker stopped; see authenticated job API for failureCode')
            time.sleep(10)
        raise CheckFailed('Worker did not reach ' + wanted + ' within the deadline; no automatic paid retry')

    def asset_bytes(self, url):
        require(url.startswith(PROJECT + '/storage/v1/object/sign/dog-assets/'), 'Unexpected signed asset location')
        return self.request('GET', url, raw=True)

    def rig(self):
        job = self.wait_for('RIG_REVIEW')
        require(self.submissions() == 1, 'More than BASE was submitted before rig review')
        require(all(s['status'] == 'PENDING' for s in job['steps'] if s['action'] != 'BASE'),
                'Animation advanced before rig confirmation')
        rig = self.api('GET', self.job_path + '/rig')['data']
        require(rig['reviewRequired'] is True and isinstance(rig['profile'], dict), 'Missing rig review')
        base = self.asset_bytes(rig['baseUrl'])
        check_sheet(base, 1)
        (self.output / 'base.png').write_bytes(base)
        (self.output / 'rig.json').write_text(json.dumps(rig['profile'], indent=2), encoding='utf-8')
        # Exercise reviewer API only for this explicitly disposable synthetic fixture.
        # This is NOT a claim that bounding-box fitting meets production visual quality.
        self.api('POST', self.job_path + '/review', {'decision': 'APPROVE'}, (409,))
        self.api('POST', self.job_path + '/rig/confirm', {
            'expectedRevision': rig['expectedRevision'] + 1, 'profile': rig['profile']}, (409,))
        confirmed = self.api('POST', self.job_path + '/rig/confirm', {
            'expectedRevision': rig['expectedRevision'], 'profile': rig['profile']})['data']
        require(confirmed['rigConfirmed'] is True, 'Rig confirmation failed')
        self.passed('RIG_REVIEW blocks generation; early approval and stale revision rejected; rig API exercised')

    def verify_outputs(self):
        job = self.wait_for('REVIEW')
        require(all(step['status'] == 'SUCCEEDED' for step in job['steps']), 'Incomplete action')
        manifest = self.api('GET', self.job_path + '/preview')['data']
        check_manifest(manifest, PLAN - {'BASE'})
        frames = {}
        for action, clip in manifest['animations'].items():
            data = self.asset_bytes(clip['spritesheetUrl'])
            distinct = check_sheet(data, clip['frameCount'])
            (self.output / (action.lower() + '.png')).write_bytes(data)
            frames[action] = {'frameCount': clip['frameCount'], 'distinctFrames': distinct,
                              'sha256': hashlib.sha256(data).hexdigest()}
        self.report['animations'] = frames
        self.report['providerSubmissions'] = self.submissions()
        require(self.report['providerSubmissions'] == 3, 'Expected BASE/IDLE/SIT paid calls only')
        submitted_actions = self.query('SELECT action FROM shelter.asset_submissions WHERE job_id='
                                       + sql_literal(self.job) + ' ORDER BY action;').splitlines()
        require(submitted_actions == ['BASE', 'IDLE', 'SIT'], 'Harness action was sent to PixelLab')
        self.report['paidActions'] = submitted_actions
        self.passed('Server generated real animation sheets: transparent PNG, frame bounds/timing and non-static frames')
        self.passed('WALK/RUN came from server harness; exactly 3 PixelLab submissions for BASE/IDLE/SIT')

    def publication(self):
        public_path = '/v1/dogs/' + self.dog + '/assets'
        dog = self.api('GET', self.dog_path)['data']
        self.api('PATCH', self.dog_path, {'expectedUpdatedAt': dog['updatedAt'], 'isPublic': True})
        self.api('GET', public_path, expected=(404,), anonymous=True)
        approved = self.api('POST', self.job_path + '/review', {'decision': 'APPROVE'})['data']
        require(approved['status'] == 'APPROVED', 'Final review was not applied')
        published = self.api('GET', public_path, anonymous=True)['data']
        check_manifest(published, PLAN - {'BASE'})
        require(published['status'] == 'APPROVED' and published['id'] == self.job, 'Wrong published job')
        require(published['behaviorRevision'] == self.report['behaviorRevision'], 'Wrong published behavior version')
        playback = self.api('GET', '/v1/dogs/' + self.dog + '/behavior', anonymous=True)['data']
        require(playback['revision'] == published['behaviorRevision'], 'Behavior/asset version mismatch')
        for action, clip in published['animations'].items():
            require(hashlib.sha256(self.asset_bytes(clip['spritesheetUrl'])).hexdigest()
                    == self.report['animations'][action]['sha256'], 'Published PNG differs from reviewed PNG')
        self.passed('Before approval: 404; after review API: anonymous frontend manifest and identical signed PNGs')

    def cleanup(self):
        q = sql_literal
        if self.user_id:
            self.query(f"""BEGIN;
              UPDATE shelter.asset_source_permissions SET revoked_at=now()
                WHERE id={q(self.permission)} AND source_key={q(self.run)};
              UPDATE shelter.asset_jobs SET status='CANCELLED',lease_token=NULL,lease_until=NULL
                WHERE permission_id={q(self.permission)};
              UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE dog_id IN
                (SELECT id FROM shelter.dogs WHERE introduction={q(self.run)} AND shelter_id={q(SHELTER)});
              UPDATE shelter.dogs SET archived_at=now(),is_public=false
                WHERE introduction={q(self.run)} AND shelter_id={q(SHELTER)};
              UPDATE shelter.shelter_memberships SET status='REVOKED'
                WHERE id={q(self.member)} AND user_id={q(self.user_id)};
              UPDATE shelter.app_users SET disabled_at=now()
                WHERE id={q(self.user_id)} AND auth_subject={q(self.auth_id)};
              COMMIT;""")
        if self.auth_id:
            identity = self.auth('GET', '/admin/users/' + self.auth_id)
            require(identity['app_metadata']['verification_run'] == self.run, 'Refusing to remove unrelated Auth user')
            self.auth('DELETE', '/admin/users/' + self.auth_id, expected=(200, 204))
            self.auth('GET', '/admin/users/' + self.auth_id, expected=(404,))
        if self.dog:
            self.api('GET', '/v1/dogs/' + self.dog + '/assets', expected=(404,), anonymous=True)
        if self.before:
            after = self.snapshot()
            require(all(self.before[t].issubset(after[t]) for t in self.tables), 'An existing record changed during the check')
        self.report.update(fixtureClosed=True, existingRecordsUnchanged=True)
        self.passed('Temporary login removed, fixture archived, public access revoked; existing rows and paid ledger preserved'
                    if self.auth_id else 'No fixture was created; existing rows unchanged')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-ref', required=True)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env.supabase')
    parser.add_argument('--storage-env-file', type=Path, default=BACKEND / '.env.storage')
    parser.add_argument('--output-dir', type=Path, required=True, help='new local private directory outside Git')
    parser.add_argument('--psql')
    parser.add_argument('--ca-file', help='optional trusted CA bundle; TLS verification is always enabled')
    parser.add_argument('--timeout-seconds', type=int, default=1200)
    parser.add_argument('--allow-paid-calls', action='store_true', help='one behavior analysis and three PixelLab submissions')
    parser.add_argument('--exercise-synthetic-review-apis', action='store_true',
                        help='exercise both review APIs for the synthetic fixture; does not certify visual quality')
    args = parser.parse_args()
    if not args.allow_paid_calls or not args.exercise_synthetic_review_apis:
        parser.error('Both explicit paid-call and synthetic-review flags are required; nothing was sent.')
    if not 60 <= args.timeout_seconds <= 1800:
        parser.error('timeout-seconds must be 60..1800')
    check = None
    failed = False
    try:
        check = LiveCheck(args)
        observation = check.setup()
        check.behavior(observation)
        check.upload()
        check.rig()
        check.verify_outputs()
        check.publication()
    except (Exception, KeyboardInterrupt) as error:
        failed = True
        # Unknown exception messages can contain URLs/tokens; never print them.
        message = str(error) if isinstance(error, (CheckFailed, ConfigurationError)) else type(error).__name__
        print('FAIL ' + message, flush=True)
        if check:
            check.report.update(status='FAILED', failure=message)
    finally:
        if check:
            try:
                check.cleanup()
            except Exception:
                failed = True
                check.report['cleanupNeedsAttention'] = True
                print('FAIL cleanup needs attention; use fixture IDs in the private report', flush=True)
            check.report['status'] = 'FAILED' if failed else 'PASSED'
            check.save()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
