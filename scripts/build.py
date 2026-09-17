"""Rebuild the standalone preview using only the Python standard library."""
from html import escape
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
fragment = root / 'outputs' / 'joystick-shelter-mobile.html'
subprocess.run([
    sys.executable,
    str(root / 'work' / 'mobile-concept' / 'build-walk.py'),
    '--output', str(fragment),
], check=True)

template = (root / 'scripts' / 'standalone-template.html').read_text(encoding='utf-8')
marker = '<!--__SHELTER_CONNECT_FRAGMENT__-->'
assert template.count(marker) == 1, 'Expected one fragment insertion point'
document = template.replace(marker, escape(fragment.read_text(encoding='utf-8')))
(root / 'index.html').write_text(document, encoding='utf-8')
print('Built index.html. Open it in a browser to try the prototype.')
