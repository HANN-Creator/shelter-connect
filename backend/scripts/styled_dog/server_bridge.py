"""Credential-free, bounded payload builder used by the Spring worker."""
import sys
from pathlib import Path
from .client import read, write, image_argument
from .pipeline import prepare, load_rules, motion_payload


def main():
    root = Path(sys.argv[1])
    request = read(root / 'input.json')
    if request['mode'] == 'character':
        traits = request['traits']
        # Server uses its dog UUID as the identity and an immutable stored photo.
        traits.update(animalId=request['dogId'], sourcePhoto='photo-1.png', photoReviewed=True)
        write(root / 'source.json', {'desertionNo': request['dogId']})
        write(root / 'traits.json', traits)
        payload = prepare(root, root / 'traits.json')
    elif request['mode'] == 'motion':
        payload = motion_payload(request['traits'], load_rules(), request['action'],
                                 request['direction'], image_argument(root / 'seed.png'))
    else:
        raise ValueError('Unknown mode')
    write(root / 'payload.json', payload)


if __name__ == '__main__':
    main()
