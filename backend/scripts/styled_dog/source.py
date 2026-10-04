"""Fetch one public dog and prepare reviewed full-body / face reference images."""
import hashlib
import io
from pathlib import Path
import urllib.parse

from PIL import Image, ImageOps
from .client import download, read, secret, write

ENDPOINT = 'https://apis.data.go.kr/1543061/abandonmentPublicService_v2/abandonmentPublic_v2'
FIELDS = {'desertionNo','noticeNo','kindCd','kindNm','colorCd','age','weight','sexCd',
          'specialMark','popfile1','popfile2','careNm','processState'}


def secure_photo_url(url):
    p = urllib.parse.urlparse(url)
    if p.scheme not in ('http', 'https') or p.hostname != 'openapi.animal.go.kr' or p.username or p.password or p.port:
        raise ValueError('Unexpected government photo URL')
    return urllib.parse.urlunparse(p._replace(scheme='https'))


def fetch_source(root, context, env_file=None, animal_id=None, exclude=()):
    import json
    root = Path(root)
    if (root / 'source.json').exists():
        source = read(root / 'source.json')
        if (animal_id and str(source['desertionNo']) != animal_id) or str(source['desertionNo']) in exclude:
            raise ValueError('Existing run contains a different/excluded dog')
        # A partial download is resumed for the SAME dog, never replaced silently.
    else:
        key = secret('DATA_GO_KR_SERVICE_KEY', env_file)
        query = {'serviceKey': urllib.parse.unquote(key), 'upkind':'417000', 'state':'protect',
                 'pageNo':1, 'numOfRows':100, '_type':'json'}
        try:
            payload = json.loads(download(ENDPOINT + '?' + urllib.parse.urlencode(query), context))
            items = payload['response']['body']['items']['item']
            if isinstance(items, dict):
                items = [items]
            animal = next(x for x in items if x.get('popfile1') and str(x['desertionNo']) not in exclude
                          and (not animal_id or str(x['desertionNo']) == animal_id))
        except Exception:
            raise RuntimeError('Public dog lookup failed; credential-bearing URL was not logged') from None
        source = {k:v for k,v in animal.items() if k in FIELDS}
        write(root / 'source.json', source)
    for n in (1, 2):
        if source.get('popfile' + str(n)) and not (root / f'photo-{n}.png').exists():
            data = download(secure_photo_url(source['popfile' + str(n)]), context)
            with Image.open(io.BytesIO(data)) as image:
                if image.width * image.height > 20_000_000:
                    raise ValueError('Source image too large')
                ImageOps.exif_transpose(image).convert('RGB').save(root / f'photo-{n}.png')
    return source


def prepare_concept(root, traits):
    source = read(root / 'source.json')
    if str(traits['animalId']) != str(source['desertionNo']):
        raise ValueError('Traits belong to another dog')
    if traits.get('photoReviewed') is not True or not traits.get('reviewNote', '').strip():
        raise ValueError('Photo features and face crop must be visually reviewed')
    if traits.get('sourcePhoto') not in ('photo-1.png', 'photo-2.png'):
        raise ValueError('Choose a downloaded source photo')
    data = (root / traits['sourcePhoto']).read_bytes()
    if hashlib.sha256(data).hexdigest() != traits['sourcePhotoSha256']:
        raise ValueError('Source photo changed after review')
    bbox = traits['faceBox']
    if len(bbox) != 4 or any(type(v) not in (int,float) or not 0 <= v <= 1 for v in bbox):
        raise ValueError('Face crop must contain four normalized coordinates')
    left, top, right, bottom = bbox
    if left >= right or top >= bottom:
        raise ValueError('Face crop is empty')
    with Image.open(io.BytesIO(data)) as photo:
        photo = photo.convert('RGB')
        face = photo.crop((round(left*photo.width),round(top*photo.height),round(right*photo.width),round(bottom*photo.height)))
        if min(face.size) < 8:
            raise ValueError('Face crop is too small')
        face.save(root / 'face.png')
        # Only photographic references are rescaled. Generated pixel assets never are.
        concept = Image.new('RGB', (1024, 512), '#eeeeee')
        for index, image in enumerate((photo, face)):
            tile = ImageOps.contain(image, (496,496), Image.Resampling.LANCZOS)
            concept.paste(tile, (index*512+(512-tile.width)//2, (512-tile.height)//2))
        concept.save(root / 'photo-concept.png')
