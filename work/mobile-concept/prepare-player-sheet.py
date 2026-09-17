from pathlib import Path
from PIL import Image, ImageDraw

base = Path(__file__).parent
source = Image.open(base / 'player-animation-sheet.png').convert('RGBA')

# Some feet extend one pixel into the next 24px row. Locate the complete
# silhouettes first, then pack them into clean cells without cutting feet off.
column_runs = []
for col in range(8):
    alpha = source.crop((col * 24, 0, (col + 1) * 24, source.height)).getchannel('A')
    runs = []
    start = None
    for y in range(source.height + 1):
        occupied = y < source.height and alpha.crop((0, y, 24, y + 1)).getbbox()
        if occupied and start is None:
            start = y
        elif not occupied and start is not None:
            runs.append((start, y))
            start = None
    column_runs.append(runs)

CELL_WIDTH, CELL_HEIGHT = 24, 32
atlas = Image.new('RGBA', (CELL_WIDTH * 4, CELL_HEIGHT * 6))
def put(source_row, source_col, dest_row, dest_col):
    top, bottom = column_runs[source_col][source_row]
    frame = source.crop((source_col * 24, top, (source_col + 1) * 24, bottom))
    # Preserve the artist's one-pixel vertical motion, including the feet that
    # cross a nominal 24px cell. The extra padding prevents adjacent-row leaks.
    y = 2 + top - source_row * 24
    assert 0 <= y and y + frame.height <= CELL_HEIGHT
    atlas.alpha_composite(frame, (dest_col * CELL_WIDTH, dest_row * CELL_HEIGHT + y))

for col in range(2):
    put(0, col, 0, col)  # IDLE: first row, columns 0–1.
    put(5, col, 5, col)  # SIT: last row, columns 0–1; hold the seated frame.
for col in range(4):
    put(1, col, 1, col)  # WALK: second row, columns 0–3.
    put(1, col + 4, 2, col)  # RUN: second row, columns 4–7.
    put(2, col, 3, col)  # PUSH: third row, columns 0–3; throwing gesture.
    put(2, col + 4, 4, col)  # PULL: third row, columns 4–7; receiving gesture.
atlas.save(base / 'player-walk-atlas.png')

preview = Image.new('RGB', (624, 1152), '#e8eedc')
draw = ImageDraw.Draw(preview)
for row, label in enumerate(('IDLE · 2 frames', 'WALK · 4 frames', 'RUN · 4 frames', 'PUSH · throw', 'PULL · receive', 'SIT · hold seated pose')):
    draw.text((12, row * 192 + 10), label, fill='#35482f')
    strip = atlas.crop((0, row * CELL_HEIGHT, atlas.width, (row + 1) * CELL_HEIGHT))
    strip = strip.resize((576, 192), Image.Resampling.NEAREST)
    preview.paste(strip, (24, row * 192), strip)
preview.save(base / 'player-atlas-preview.png')
print('Prepared IDLE/SIT (2 each), WALK/RUN/PUSH/PULL (4 each) from the labeled reference.')
