"""Run the opt-in real Supabase login/storage check with temporary ordinary users."""
import argparse
import os
from pathlib import Path
import sys

from run_supabase import BACKEND, ConfigurationError, launch_settings, load_settings, load_storage_settings


def check_settings(values, project_ref):
    if not project_ref or values['SUPABASE_URL'] != f'https://{project_ref}.supabase.co':
        raise ConfigurationError('확인할 개발 프로젝트 ref가 로컬 설정과 일치해야 해요.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-ref', required=True, help='explicit development project ref; must match the local configuration')
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env.supabase')
    parser.add_argument('--storage-env-file', type=Path, default=BACKEND / '.env.storage')
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        check_settings(values, args.project_ref)
        storage = load_storage_settings(args.storage_env_file)
        env, _ = launch_settings(values, os.environ)
        env['SUPABASE_SECRET_KEY'] = storage['SUPABASE_SECRET_KEY']
        env['AUTH_CHECK_PROJECT_REF'] = args.project_ref
        command = [str(BACKEND / 'gradlew'), '--no-daemon', 'checkLoginStorage']
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
