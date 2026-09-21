"""Run the opt-in real Supabase login/storage check with temporary ordinary users."""
import argparse
import os
import re
from pathlib import Path
import sys

from run_supabase import BACKEND, ConfigurationError, launch_settings, load_settings, load_storage_settings


def check_settings(values, project_ref):
    if not project_ref or values['SUPABASE_URL'] != f'https://{project_ref}.supabase.co':
        raise ConfigurationError('확인할 개발 프로젝트 ref가 로컬 설정과 일치해야 해요.')


def deployment_origin(value):
    if not re.fullmatch(r'https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.onrender\.com', value):
        raise argparse.ArgumentTypeError('Render의 HTTPS 기본 주소만 입력해 주세요. 경로·포트·토큰은 넣지 않아요.')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-ref', required=True, help='explicit development project ref; must match the local configuration')
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env.supabase')
    parser.add_argument('--storage-env-file', type=Path, default=BACKEND / '.env.storage')
    parser.add_argument('--api-origin', type=deployment_origin,
                        help='opt-in deployed Render HTTPS origin; never restarts the hosted server')
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        check_settings(values, args.project_ref)
        storage = load_storage_settings(args.storage_env_file)
        env, _ = launch_settings(values, os.environ)
        env['SUPABASE_SECRET_KEY'] = storage['SUPABASE_SECRET_KEY']
        env['AUTH_CHECK_PROJECT_REF'] = args.project_ref
        task = 'checkLoginStorage'
        if args.api_origin:
            env['DEPLOYMENT_CHECK_ORIGIN'] = args.api_origin
            task = 'checkDeployedStorage'
        command = [str(BACKEND / 'gradlew'), '--no-daemon', task]
        os.chdir(BACKEND)
        os.execve(command[0], command, env)
    except ConfigurationError as error:
        print(str(error), file=sys.stderr)
        return 2
    except OSError:
        print('검증 도구를 실행하지 못했어요. Java 21과 실행 권한을 확인해 주세요.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
