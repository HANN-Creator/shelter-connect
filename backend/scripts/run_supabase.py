"""Start the local API with a Git-ignored Supabase configuration, without shell expansion."""
import argparse
import os
from pathlib import Path
import re
import shutil
import sys
from urllib.parse import parse_qs, urlsplit

BACKEND = Path(__file__).resolve().parents[1]
KEYS = {"DB_URL", "DB_USERNAME", "DB_PASSWORD", "SUPABASE_URL", "DB_POOL_SIZE", "PORT"}


class ConfigurationError(Exception):
    pass


def load_settings(path):
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError):
        raise ConfigurationError("설정 파일을 UTF-8 일반 텍스트로 저장해 주세요.") from None
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or key not in KEYS or key in values:
            raise ConfigurationError("설정 파일의 키와 중복 항목을 확인해 주세요.")
        # Passwords are literal. Never source this file or expand $(), quotes, or backticks.
        values[key] = value if key == "DB_PASSWORD" else value.strip()
    for key in ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "SUPABASE_URL"):
        if not values.get(key) or values[key].startswith("YOUR_"):
            raise ConfigurationError(f"{key} 설정이 필요해요. 값은 출력하지 않았어요.")
    if any("\x00" in value for value in values.values()):
        raise ConfigurationError("설정 값에 NUL 문자를 넣을 수 없어요.")
    try:
        project = urlsplit(values["SUPABASE_URL"])
        if not re.fullmatch(r"[a-z0-9]{20}\.supabase\.co", project.hostname or ""):
            raise ValueError()
        if project.scheme != "https" or project.username or project.password or project.port or project.path or project.query or project.fragment:
            raise ValueError()
        ref = project.hostname.split(".")[0]
        if values["DB_USERNAME"] != "postgres." + ref:
            raise ValueError()
        url = values["DB_URL"]
        if not url.startswith("jdbc:postgresql://"):
            raise ValueError()
        database = urlsplit(url[5:])
        if not re.fullmatch(r"aws-[0-9]+-[a-z0-9-]+\.pooler\.supabase\.com", database.hostname or ""):
            raise ValueError()
        if database.username or database.password or database.port != 5432 or database.path != "/postgres" or database.fragment:
            raise ValueError()
        if parse_qs(database.query, strict_parsing=True) != {"sslmode": ["require"]}:
            raise ValueError()
        for key, default, low, high in (("DB_POOL_SIZE", "3", 1, 5), ("PORT", "8080", 1024, 65535)):
            value = values.setdefault(key, default)
            if not value.isascii() or not value.isdecimal() or not low <= int(value) <= high:
                raise ValueError()
    except ValueError:
        raise ConfigurationError("프로젝트 URL, 세션 풀러(5432/SSL) 주소, 사용자 이름, 포트·풀 크기를 확인해 주세요.") from None
    return values


def launch_settings(values, inherited, read_only=False):
    # Prevent unrelated local Spring/AI settings from overriding the selected development target.
    prefixes = ("SPRING_", "DB_", "SERVER_", "SUPABASE_", "AI_", "OPENAI_", "PHOTO_")
    env = {key: value for key, value in inherited.items() if not key.startswith(prefixes)
           and key not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
    env.update(values)
    env.update(DB_MIGRATE="false", SERVER_ADDRESS="127.0.0.1", AI_ENABLED="false", PHOTO_STORAGE_ENABLED="false")
    args = ["--spring.config.location=classpath:/application.properties", "--spring.flyway.enabled=false",
            "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
            "--server.address=127.0.0.1", "--app.ai.enabled=false", "--app.photos.enabled=false"]
    if read_only:
        args.append("--spring.datasource.hikari.read-only=true")
    return env, args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=BACKEND / ".env.supabase")
    parser.add_argument("--check-config", action="store_true", help="validate local settings without connecting or printing values")
    parser.add_argument("--read-only", action="store_true", help="make JDBC transactions read-only for connection verification")
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        if args.check_config:
            print("로컬 연결 설정 형식 확인 완료. 비밀번호는 출력하지 않았고 DB에 접속하지 않았어요.")
            return 0
        jar = BACKEND / "build/libs/shelter-connect-api.jar"
        if not jar.is_file():
            raise ConfigurationError("먼저 backend에서 ./gradlew bootJar로 서버를 빌드해 주세요.")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        if not java or not Path(java).is_file():
            raise ConfigurationError("Java 21 경로를 JAVA_HOME에 설정해 주세요.")
        env, flags = launch_settings(values, os.environ, args.read_only)
        print(f"Supabase 개발 연결로 로컬 서버 시작: http://127.0.0.1:{values['PORT']} (읽기 전용: {args.read_only})", flush=True)
        os.chdir(BACKEND)
        os.execve(java, [java, "-jar", str(jar), *flags], env)
    except ConfigurationError as error:
        print(str(error), file=sys.stderr)
        return 2
    except OSError:
        print("서버 실행에 실패했어요. Java 경로와 로컬 실행 권한을 확인해 주세요.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
