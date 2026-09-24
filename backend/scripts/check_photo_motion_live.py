"""Development photo E2E with explicit, hash-bound local rig and animation review gates."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import time

from PIL import Image

from check_asset_pipeline_live import CheckFailed, ConfigurationError, PLAN, require, sql_literal
from check_photo_appearance_live import PhotoCheck, NOTE


def review_decision(document, expected):
    require(isinstance(document, dict), 'Review must be a JSON object')
    require(all(document.get(key) == value for key, value in expected.items()),
            'Review belongs to a different job, stage, revision or image')
    require(isinstance(document.get('note'), str) and len(document['note'].strip()) >= 10,
            'Record what was visually inspected in the review note')
    require(document.get('decision') in ('APPROVE', 'REJECT'), 'Review decision must be explicit')
    require(document['decision'] == 'APPROVE', 'Reviewer rejected this output; no publication')
    return document


def local_manifest(manifest):
    # Only playback fields belong in the local preview. Never copy signed URLs.
    result = {key: manifest[key] for key in
              ('schemaVersion', 'facing', 'availableActions', 'frameSize', 'anchorPixels',
               'fallbackAction', 'behaviorRevision')}
    result['baseUrl'] = 'base.png'
    result['animations'] = {}
    for action, clip in manifest['animations'].items():
        result['animations'][action] = {key: clip[key] for key in
                                       ('frameCount', 'loop', 'holdLastFrame', 'returnToIdle', 'frames')}
        result['animations'][action]['spritesheetUrl'] = action.lower() + '.png'
    return result


class PhotoMotionCheck(PhotoCheck):
    def prepare(self):
        observation = self.setup()
        self.query('UPDATE shelter.asset_source_permissions SET permission_note=' + sql_literal(NOTE)
                   + ' WHERE id=' + sql_literal(self.permission) + ' AND source_key=' + sql_literal(self.run) + ';')
        self.report.update(purpose='Real-photo full motion verification with visual review',
                           expectedServerCommit=self.args.server_commit, publicationPerformed=False,
                           behaviorNote='Fixture observations are fictional, not claims about the photographed dog')
        self.behavior(observation)

    def wait_review(self, stage, binding):
        expected = dict(binding, stage=stage, jobId=self.job)
        request = dict(expected, decision='PENDING', note='')
        path = self.output / (stage + '-review.json')
        (self.output / (stage + '-review-request.json')).write_text(json.dumps(request, indent=2))
        self.report['stage'] = 'AWAITING_' + stage.upper() + '_REVIEW'
        self.save()
        print('REVIEW write inspected decision to ' + str(path), flush=True)
        deadline = time.monotonic() + self.args.review_timeout_seconds
        while time.monotonic() < deadline:
            if path.exists():
                require(path.stat().st_size <= 64 * 1024, 'Review file exceeds size limit')
                decision = review_decision(json.loads(path.read_text()), expected)
                self.report[stage + 'Review'] = {key: decision[key] for key in expected | {'decision': '', 'note': ''}}
                self.save()
                return decision
            time.sleep(3)
        raise CheckFailed('Visual review timed out; no automatic approval')

    def reviewed_rig(self):
        self.download_base()
        job = self.api('GET', self.job_path)['data']
        require(set(job['actionPlan']) == PLAN, 'Unexpected generated action plan')
        require(job['behaviorRevision'] == self.report['behaviorRevision'], 'Wrong behavior revision')
        rig = self.api('GET', self.job_path + '/rig')['data']
        require(rig['reviewRequired'] is True, 'Rig review must be required')
        (self.output / 'rig-proposal.json').write_text(json.dumps(rig['profile'], indent=2))
        self.api('POST', self.job_path + '/review', {'decision': 'APPROVE'}, (409,))
        self.api('POST', self.job_path + '/rig/confirm', {
            'expectedRevision': rig['expectedRevision'] + 1, 'profile': rig['profile']}, (409,))
        decision = self.wait_review('rig', {'baseSha256': self.report['baseSha256'],
                                          'expectedRevision': rig['expectedRevision']})
        require(isinstance(decision.get('profile'), dict), 'Reviewed rig profile is required')
        (self.output / 'rig-reviewed.json').write_text(json.dumps(decision['profile'], indent=2))
        confirmed = self.api('POST', self.job_path + '/rig/confirm', {
            'expectedRevision': rig['expectedRevision'], 'profile': decision['profile']})['data']
        require(confirmed['rigConfirmed'] is True, 'Rig confirmation failed')
        self.passed('Inspected rig confirmed; early publication and stale rig revision rejected')

    def reviewed_publication(self):
        manifest = self.api('GET', self.job_path + '/preview')['data']
        (self.output / 'manifest.json').write_text(json.dumps(local_manifest(manifest), indent=2))
        preview = self.output / 'preview'
        preview.mkdir()
        for name in ('input.png', 'base.png', 'idle.png', 'walk.png', 'run.png', 'sit.png', 'manifest.json'):
            shutil.copyfile(self.output / name, preview / name)
        shutil.copyfile(Path(__file__).with_name('photo_motion_preview.html'), preview / 'index.html')
        hashes = {action: item['sha256'] for action, item in self.report['animations'].items()}
        self.wait_review('animation', {'animationSha256': hashes})
        # Ensure a local preview was not edited after inspection.
        require(all(hashlib.sha256((self.output / (action.lower() + '.png')).read_bytes()).hexdigest() == digest
                    for action, digest in hashes.items()), 'Inspected animation file changed')
        self.publication()
        self.report['publicationPerformed'] = True
        self.report['stage'] = 'VERIFIED'
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('project-ref', 'server-commit'):
        parser.add_argument('--' + name, required=True)
    for name in ('photo', 'env-file', 'storage-env-file', 'output-dir'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--psql')
    parser.add_argument('--ca-file', default='/etc/ssl/cert.pem')
    parser.add_argument('--timeout-seconds', type=int, default=1200)
    parser.add_argument('--review-timeout-seconds', type=int, default=1800)
    parser.add_argument('--allow-paid-calls', action='store_true')
    parser.add_argument('--exercise-temporary-publication', action='store_true')
    args = parser.parse_args()
    if not args.allow_paid_calls or not args.exercise_temporary_publication:
        parser.error('Explicit paid-call and temporary development publication flags are required; nothing sent')
    if not all(60 <= value <= 1800 for value in (args.timeout_seconds, args.review_timeout_seconds)):
        parser.error('Timeouts must be 60..1800 seconds')
    # Reject an unusable input before creating fixtures or paying for behavior analysis.
    try:
        with Image.open(args.photo) as photo:
            require(photo.format == 'PNG' and max(photo.size) <= 1024,
                    'Use a rights-cleared PNG up to 1024px for this bounded check')
            photo.load()
    except Exception:
        parser.error('Input must be a readable PNG up to 1024px; nothing was sent')
    check, failed = None, False
    try:
        check = PhotoMotionCheck(args)
        check.prepare()
        check.upload_photo()
        check.reviewed_rig()
        check.verify_outputs()
        check.reviewed_publication()
    except (Exception, KeyboardInterrupt) as error:
        failed = True
        message = str(error) if isinstance(error, (CheckFailed, ConfigurationError)) else type(error).__name__
        print('FAIL ' + message, flush=True)
        if check:
            check.report['failure'] = message
    finally:
        if check:
            try:
                check.cleanup()
            except Exception:
                failed = True
                check.report['cleanupNeedsAttention'] = True
                print('FAIL cleanup needs attention', flush=True)
            check.report['status'] = 'FAILED' if failed else 'PASSED'
            check.save()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
