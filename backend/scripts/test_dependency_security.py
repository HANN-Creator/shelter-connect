import io
import urllib.error
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from check_dependency_security import ScanError, packages, scan, main, post_osv, MAX_RESPONSE

MAVEN = [{'group': 'org.example', 'name': 'library', 'version': '1.2.3'}]
PYTHON = [{'name': 'Pillow', 'version': '12.3.0'}, {'name': 'numpy', 'version': '2.2.6'}]
PINS = 'Pillow==12.3.0\nnumpy==2.2.6\n'

class DependencySecurityTest(unittest.TestCase):
    def test_inventory_uses_resolved_versions_and_normalizes_python_names(self):
        inventory = packages(MAVEN + MAVEN, PYTHON, PINS)
        self.assertEqual(len(inventory), 3)
        self.assertIn({'package': {'ecosystem': 'PyPI', 'name': 'pillow'}, 'version': '12.3.0'}, inventory)

    def test_missing_or_inconsistent_inventory_fails(self):
        for maven, python, pins in [([], PYTHON, PINS), (MAVEN, [], PINS), (MAVEN, PYTHON, 'Pillow==1.0'), (MAVEN, PYTHON, 'Pillow>=12'), (MAVEN, PYTHON, '')]:
            with self.subTest(pins=pins), self.assertRaises(ScanError): packages(maven, python, pins)

    def test_clean_results_need_every_query_to_be_answered(self):
        inventory = packages(MAVEN, PYTHON, PINS)
        self.assertEqual(scan(inventory, lambda q: {'results': [{} for _ in q]}), [])
        for response in [{}, {'results': []}, {'results': [{}]}, {'results': [None]*3}, {'results': [{'error': 'failed'}]*3}, {'results': [{'vulns': 'invalid'}]*3}]:
            with self.subTest(response=response), self.assertRaises(ScanError): scan(inventory, lambda q: response)

    def test_paginated_advisories_cannot_be_silently_dropped(self):
        inventory = packages(MAVEN, PYTHON, PINS)[:1]
        calls = []
        def transport(queries):
            calls.append(queries)
            if len(calls) == 1: return {'results': [{'next_page_token': 'page-2'}]}
            return {'results': [{'vulns': [{'id': 'GHSA-test-found'}]}]}
        self.assertEqual(scan(inventory, transport)[0]['advisories'], ['GHSA-test-found'])
        self.assertEqual(calls[1][0]['page_token'], 'page-2')

    def test_repeated_or_endless_pagination_fails(self):
        inventory = packages(MAVEN, PYTHON, PINS)[:1]
        with self.assertRaises(ScanError): scan(inventory, lambda q: {'results': [{'next_page_token': 'same'}]})
        count = 0
        def endless(q):
            nonlocal count
            count += 1
            return {'results': [{'next_page_token': str(count)}]}
        with self.assertRaises(ScanError): scan(inventory, endless)
        self.assertEqual(count, 10)

    def test_large_inventory_is_batched_without_omitting_packages(self):
        inventory = [{'package': {'ecosystem': 'Maven', 'name': f'a:b{i}'}, 'version': '1'} for i in range(205)]
        seen = []
        def transport(q):
            seen.extend(q); self.assertLessEqual(len(q), 100)
            return {'results': [{} for _ in q]}
        self.assertEqual(scan(inventory, transport), [])
        self.assertEqual(seen, inventory)

    def test_cli_distinguishes_clean_matches_and_failed_checks_and_replaces_old_report(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'maven.json').write_text(json.dumps(MAVEN)); (root/'python.json').write_text(json.dumps(PYTHON)); (root/'requirements.txt').write_text(PINS)
            arguments = ['--maven', str(root/'maven.json'), '--python', str(root/'python.json'), '--requirements', str(root/'requirements.txt'), '--output', str(root/'report.json'), '--summary', str(root/'summary.md')]
            for matches, code, status in [([], 0, 'clean'), ([{'ecosystem':'Maven','name':'a:b','version':'1','advisories':['GHSA-test']}], 1, 'vulnerable')]:
                with patch('check_dependency_security.scan', return_value=matches), patch('builtins.print'):
                    self.assertEqual(main(arguments), code)
                self.assertEqual(json.loads((root/'report.json').read_text())['status'], status)
            with patch('check_dependency_security.scan', side_effect=RuntimeError('private-value')), patch('builtins.print'):
                self.assertEqual(main(arguments), 2)
            report = (root/'report.json').read_text()
            self.assertEqual(json.loads(report)['status'], 'error'); self.assertNotIn('private-value', report)

    def test_network_failures_retry_bounded_times_and_do_not_return_a_clean_result(self):
        with patch('check_dependency_security.urllib.request.urlopen', side_effect=urllib.error.URLError('offline')) as request, patch('check_dependency_security.time.sleep'):
            with self.assertRaises(ScanError): post_osv([])
            self.assertEqual(request.call_count, 3)
        error = urllib.error.HTTPError('https://api.osv.dev', 400, 'bad request', {}, None)
        with patch('check_dependency_security.urllib.request.urlopen', side_effect=error) as request:
            with self.assertRaises(ScanError): post_osv([])
            self.assertEqual(request.call_count, 1)

    def test_remote_response_is_bounded_before_json_parsing(self):
        with patch('check_dependency_security.urllib.request.urlopen', return_value=io.BytesIO(b'x' * (MAX_RESPONSE + 1))):
            with self.assertRaises(ScanError): post_osv([])
