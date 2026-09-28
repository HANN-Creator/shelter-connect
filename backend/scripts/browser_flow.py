"""Run the local browser flow harness; keys stay in the local helper process."""
import argparse
import os
from pathlib import Path
import sys
from check_login_storage import check_settings, deployment_origin
from run_supabase import BACKEND, ConfigurationError, launch_settings, load_settings, load_storage_settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-ref', required=True)
    parser.add_argument('--api-origin', required=True, type=deployment_origin)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env.admin-tls')
    parser.add_argument('--storage-env-file', type=Path, default=BACKEND / '.env.storage')
    parser.add_argument('--port', type=int, choices=range(1024, 65536), default=8891, metavar='1024..65535')
    parser.add_argument('--allow-paid-ai', action='store_true', help='Allow up to 3 explicit reply requests, using fictional records only')
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        check_settings(values, args.project_ref)
        storage = load_storage_settings(args.storage_env_file)
        env, _ = launch_settings(values, os.environ)
        env.update(AUTH_CHECK_PROJECT_REF=args.project_ref,
                   DEPLOYMENT_CHECK_ORIGIN=args.api_origin,
                   SUPABASE_SECRET_KEY=storage['SUPABASE_SECRET_KEY'],
                   PHOTO_STORAGE_BUCKET=storage['PHOTO_STORAGE_BUCKET'],
                   WEB_CHECK_PORT=str(args.port), WEB_CHECK_PAID_AI=str(args.allow_paid_ai).lower())
        os.chdir(BACKEND)
        command = [str(BACKEND / 'gradlew'), '--no-daemon', 'browserFlowCheck']
        os.execve(command[0], command, env)
    except (ConfigurationError, OSError):
        print('로컬 설정·Java 21·지정 개발 프로젝트를 확인해 주세요. 비밀 값은 출력하지 않았어요.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
