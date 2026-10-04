"""Backend CLI: source → prepare → character → review → animate → package.

No paid API is called without --allow-paid-calls. Outputs are private review
artifacts; this command never writes the application DB or publishes assets.
"""
import argparse
import fcntl
from pathlib import Path
import ssl

from styled_dog.client import Client, secret
from styled_dog.source import fetch_source
from styled_dog.pipeline import prepare, generate_character, record_review, animate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage',choices=['source','prepare','character','review','animate','package'])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--data-env-file',type=Path)
    parser.add_argument('--ca-file')
    parser.add_argument('--animal-id')
    parser.add_argument('--exclude',nargs='*',default=[])
    parser.add_argument('--traits',type=Path)
    parser.add_argument('--review-note')
    parser.add_argument('--actions',nargs='+')
    parser.add_argument('--allow-paid-calls',action='store_true')
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True,exist_ok=True)
    with (root/'.pipeline.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.stage == 'source':
            print(fetch_source(root,ssl.create_default_context(cafile=args.ca_file),args.data_env_file,args.animal_id,args.exclude))
        elif args.stage == 'prepare':
            if not args.traits:
                parser.error('--traits is required for prepare')
            prepare(root,args.traits)
        elif args.stage == 'review':
            record_review(root,args.review_note or '')
        elif args.stage == 'package':
            from styled_dog.package import package
            package(root)
        else:
            client = Client(root,secret('PIXELLAB_API_KEY',args.env_file),args.allow_paid_calls,args.ca_file)
            if args.stage == 'character':
                generate_character(root,client)
            else:
                animate(root,client,args.actions)


if __name__ == '__main__':
    main()
