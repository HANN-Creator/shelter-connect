"""Check real private Storage with the server adapter, without starting the API or writing app data."""
import argparse
import os
from pathlib import Path
import sys

from run_supabase import BACKEND, ConfigurationError, launch_settings, load_settings, load_storage_settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=BACKEND / ".env.supabase")
    parser.add_argument("--storage-env-file", type=Path, default=BACKEND / ".env.storage")
    parser.add_argument("--prepare", action="store_true", help="create a missing private bucket and a dedicated test PNG; never overwrite or delete")
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        storage = load_storage_settings(args.storage_env_file)
        env, _ = launch_settings(values, os.environ, storage=storage)
        # This check never connects to PostgreSQL and does not need its password.
        env = {k: v for k, v in env.items() if not k.startswith(("DB_", "SERVER_")) and k != "PORT"}
        command = [str(BACKEND / "gradlew"), "--no-daemon", "checkPhotoStorage"]
        if args.prepare:
            command.append("--args=--prepare")
        os.chdir(BACKEND)
        os.execve(command[0], command, env)
    except ConfigurationError as error:
        print(str(error), file=sys.stderr)
        return 2
    except OSError:
        print("저장소 검사를 실행하지 못했어요. Java 21과 Gradle 실행 권한을 확인해 주세요.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
