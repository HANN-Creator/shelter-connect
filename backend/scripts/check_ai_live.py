"""Opt-in paid AI verification against fictional data and temporary ordinary users."""
import argparse
import os
from pathlib import Path
import sys
from run_supabase import BACKEND, ConfigurationError, launch_settings, load_settings, load_storage_settings, load_ai_settings
from check_login_storage import check_settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-ref', required=True)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env.supabase')
    parser.add_argument('--storage-env-file', type=Path, default=BACKEND / '.env.storage')
    parser.add_argument('--ai-env-file', type=Path, default=BACKEND / '.env.ai')
    parser.add_argument('--check-config', action='store_true')
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        check_settings(values, args.project_ref)
        ai = load_ai_settings(args.ai_env_file)
        if ai['OPENAI_MODEL'] != 'gpt-5.6-luna':
            raise ConfigurationError('이번 검증은 합의한 gpt-5.6-luna를 사용해요. 모델 변경은 별도로 확인해 주세요.')
        storage = load_storage_settings(args.storage_env_file)
        if args.check_config:
            print('설정 형식 확인 완료. 키 값 출력·외부 호출·계정 생성은 없어요.')
            return 0
        env, _ = launch_settings(values, os.environ, ai=ai)
        env['SUPABASE_SECRET_KEY'] = storage['SUPABASE_SECRET_KEY']
        env['AUTH_CHECK_PROJECT_REF'] = args.project_ref
        command = [str(BACKEND / 'gradlew'), '--no-daemon', 'checkAiLive']
        os.chdir(BACKEND)
        os.execve(command[0], command, env)
    except ConfigurationError as error:
        print(str(error), file=sys.stderr)
        return 2
    except OSError:
        print('AI 검증 도구 실행에 실패했어요. Java 21과 실행 권한을 확인해 주세요.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
