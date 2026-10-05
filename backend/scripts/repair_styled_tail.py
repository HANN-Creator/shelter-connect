"""Explicit PixelLab edit and reviewed tail-only reconstruction; never writes DB.

Use separate output folders for different requests. The existing provider journal
resumes the same paid job; it never silently resubmits a failed/unknown request.
"""
import argparse
import base64
import fcntl
from pathlib import Path
from PIL import Image
from styled_dog.client import Client, native_image, read, secret, write
from styled_dog.tail_repair import edit_payload, read_sheet, sheet_for, compose_tail


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['edit','compose'])
    parser.add_argument('--sheet',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--direction',choices=['south','north','west','east'])
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--ca-file')
    parser.add_argument('--allow-paid-calls',action='store_true')
    parser.add_argument('--seed',type=Path)
    parser.add_argument('--source-seed',type=Path)
    parser.add_argument('--mask',type=Path)
    parser.add_argument('--review',type=Path)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'.tail-repair.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        frames=read_sheet(args.sheet)
        if args.stage=='edit':
            if not args.direction:parser.error('--direction is required')
            body=edit_payload(frames,args.direction)
            client=Client(args.output,secret('PIXELLAB_API_KEY',args.env_file),args.allow_paid_calls,args.ca_file)
            result=client.generate('tail-edit-'+args.direction,'edit-animation-v2',body)
            edited=[native_image(base64.b64decode(obj['base64'].split(',')[-1])) for obj in result['last_response']['images']]
            sheet_for(edited).save(args.output/'raw-edit.png')
            print('RAW_EDIT_SAVED_REVIEW_REQUIRED')
        else:
            if not all((args.seed,args.source_seed,args.mask,args.review)):
                parser.error('--seed, --source-seed, --mask and --review are required')
            seed=native_image(args.seed.read_bytes());source_seed=native_image(args.source_seed.read_bytes())
            with Image.open(args.mask) as image:mask=image.copy()
            candidate,report=compose_tail(seed,source_seed,frames,mask,read(args.review))
            sheet_for(candidate).save(args.output/'candidate.png')
            write(args.output/'repair-report.json',report)
            print(report['status'])


if __name__=='__main__':main()
