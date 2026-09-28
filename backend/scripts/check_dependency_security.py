#!/usr/bin/env python3
"""Fail closed on known OSV matches or incomplete checks. Sends public coordinates only."""
import argparse
import datetime
import json
import re
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path

MAX_RESPONSE = 4 * 1024 * 1024


class ScanError(Exception):
    pass


def text(value):
    if not isinstance(value, str) or not value or len(value) > 200 or any(ord(c) < 32 for c in value):
        raise ScanError('Invalid package metadata')
    return value


def packages(maven, python, requirements):
    if not isinstance(maven, list) or not maven or not isinstance(python, list) or not python:
        raise ScanError('Missing runtime dependency inventory')
    result = []
    for item in maven:
        result.append({'package': {'ecosystem': 'Maven', 'name': text(item['group']) + ':' + text(item['name'])},
                       'version': text(item['version'])})
    installed = {}
    for item in python:
        name = re.sub(r'[-_.]+', '-', text(item['name'])).lower()
        version = text(item['version'])
        if name in installed and installed[name] != version:
            raise ScanError('Conflicting Python versions')
        installed[name] = version
        result.append({'package': {'ecosystem': 'PyPI', 'name': name}, 'version': version})
    pins = 0
    for line in requirements.splitlines():
        line = line.split('#', 1)[0].strip()
        if not line:
            continue
        match = re.fullmatch(r'([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+)', line)
        if not match:
            raise ScanError('Motion requirements must use exact pins')
        name = re.sub(r'[-_.]+', '-', match[1]).lower()
        if installed.get(name) != match[2]:
            raise ScanError('Python inventory does not match motion requirements')
        pins += 1
    if not pins:
        raise ScanError('Empty motion requirements')
    unique = {json.dumps(item, sort_keys=True): item for item in result}
    if len(unique) > 2000:
        raise ScanError('Unexpectedly large inventory; review scan capacity')
    return [unique[key] for key in sorted(unique)]


def post_osv(queries):
    payload = json.dumps({'queries': queries}).encode()
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=payload,
                                    headers={'Content-Type': 'application/json', 'User-Agent': 'shelter-connect-security-check'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30, context=ssl.create_default_context()) as response:
                body = response.read(MAX_RESPONSE + 1)
                if len(body) > MAX_RESPONSE:
                    raise ScanError('OSV response exceeds the size limit')
                return json.loads(body)
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500:
                raise ScanError('OSV rejected the query') from None
        except (urllib.error.URLError, TimeoutError):
            pass
        if attempt < 2:
            time.sleep(2 ** attempt)
    raise ScanError('OSV unavailable after three attempts')


def scan(inventory, transport=post_osv):
    matches = []
    for offset in range(0, len(inventory), 100):
        pending = [(entry, dict(entry)) for entry in inventory[offset:offset + 100]]
        found = {}
        for _ in range(10):
            result = transport([query for _, query in pending])
            if not isinstance(result, dict) or set(result) != {'results'} or not isinstance(result['results'], list) or len(result['results']) != len(pending):
                raise ScanError('Incomplete OSV batch response')
            remaining = []
            for (entry, query), answer in zip(pending, result['results']):
                if not isinstance(answer, dict) or 'error' in answer:
                    raise ScanError('Invalid OSV result')
                vulns = answer.get('vulns', [])
                if not isinstance(vulns, list):
                    raise ScanError('Invalid OSV vulnerability list')
                for vuln in vulns:
                    ident = vuln.get('id') if isinstance(vuln, dict) else None
                    if not isinstance(ident, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,199}', ident):
                        raise ScanError('Invalid advisory identifier')
                    key = (entry['package']['ecosystem'], entry['package']['name'], entry['version'])
                    found.setdefault(key, set()).add(ident)
                token = answer.get('next_page_token')
                if token is not None:
                    if not isinstance(token, str) or not token or token == query.get('page_token'):
                        raise ScanError('Invalid OSV pagination token')
                    remaining.append((entry, dict(entry, page_token=token)))
            if not remaining:
                break
            pending = remaining
        else:
            raise ScanError('OSV pagination did not finish')
        for (ecosystem, name, version), advisories in sorted(found.items()):
            matches.append({'ecosystem': ecosystem, 'name': name, 'version': version, 'advisories': sorted(advisories)})
    return matches


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--maven', type=Path, required=True)
    parser.add_argument('--python', type=Path, required=True)
    parser.add_argument('--requirements', type=Path, default=Path('motion-requirements.txt'))
    parser.add_argument('--output', type=Path, default=Path('build/reports/security/result.json'))
    parser.add_argument('--summary', type=Path)
    args = parser.parse_args(argv)
    report = {'status': 'error', 'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    result = 2
    try:
        inventory = packages(json.loads(args.maven.read_text()), json.loads(args.python.read_text()), args.requirements.read_text())
        matches = scan(inventory)
        report.update(status='vulnerable' if matches else 'clean', checkedPackages=len(inventory), matches=matches)
        result = 1 if matches else 0
    except Exception as error:
        # Do not dump remote response bodies, paths, environment or raw exception messages.
        report['failureType'] = type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    lines = ['## Dependency security', '', f"Result: **{report['status']}**", '',
             f"Checked packages: {report.get('checkedPackages', 'incomplete')}", '', f"Checked at (UTC): {report['checkedAt']}", '']
    for match in report.get('matches', []):
        lines.append(f"- {match['ecosystem']} {match['name']} {match['version']}: {', '.join(match['advisories'])}")
    if result == 2:
        lines.append('The scan did not finish. Resolve the check failure and rerun; this is not a clean result.')
    if args.summary:
        with args.summary.open('a') as output:
            output.write('\n'.join(lines) + '\n')
    print(json.dumps(report))
    return result


if __name__ == '__main__':
    raise SystemExit(main())
