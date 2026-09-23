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
AI_KEYS = {"OPENAI_API_KEY", "OPENAI_MODEL", "AI_TIMEOUT_SECONDS", "BEHAVIOR_AI_DAILY_LIMIT"}
STORAGE_KEYS = {"SUPABASE_SECRET_KEY", "PHOTO_STORAGE_BUCKET", "PHOTO_STORAGE_TIMEOUT_SECONDS"}


class ConfigurationError(Exception):
    pass


def read_values(path, allowed_keys):
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
        if not separator or key not in allowed_keys or key in values:
            raise ConfigurationError("설정 파일의 키와 중복 항목을 확인해 주세요.")
        # Passwords are literal. Never source this file or expand $(), quotes, or backticks.
        values[key] = value if key == "DB_PASSWORD" else value.strip()
    if any("\x00" in value for value in values.values()):
        raise ConfigurationError("설정 값에 NUL 문자를 넣을 수 없어요.")
    return values


def load_storage_settings(path):
    values = read_values(path, STORAGE_KEYS)
    if not re.fullmatch(r"sb_secret_[A-Za-z0-9_-]+", values.get("SUPABASE_SECRET_KEY", "")):
        raise ConfigurationError("SUPABASE_SECRET_KEY에 기존 서버 키(sb_secret_)를 넣어 주세요. 값은 출력하지 않았어요.")
    bucket = values.setdefault("PHOTO_STORAGE_BUCKET", "dog-photos")
    timeout = values.setdefault("PHOTO_STORAGE_TIMEOUT_SECONDS", "5")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", bucket):
        raise ConfigurationError("PHOTO_STORAGE_BUCKET 이름을 확인해 주세요.")
    if not timeout.isascii() or not timeout.isdecimal() or not 1 <= int(timeout) <= 10:
        raise ConfigurationError("PHOTO_STORAGE_TIMEOUT_SECONDS는 1~10초로 설정해 주세요.")
    return values


def load_ai_settings(path):
    values = read_values(path, AI_KEYS)
    key = values.get("OPENAI_API_KEY", "")
    if not re.fullmatch(r"sk-[A-Za-z0-9_-]+", key):
        raise ConfigurationError("OPENAI_API_KEY를 로컬 AI 설정 파일에 넣어 주세요. 값은 출력하지 않았어요.")
    model = values.setdefault("OPENAI_MODEL", "gpt-5.6-luna")
    timeout = values.setdefault("AI_TIMEOUT_SECONDS", "30")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", model):
        raise ConfigurationError("OPENAI_MODEL 이름을 확인해 주세요.")
    if not timeout.isascii() or not timeout.isdecimal() or not 5 <= int(timeout) <= 60:
        raise ConfigurationError("AI_TIMEOUT_SECONDS는 5~60초로 설정해 주세요.")
    limit = values.setdefault("BEHAVIOR_AI_DAILY_LIMIT", "20")
    if not limit.isascii() or not limit.isdecimal() or not 1 <= int(limit) <= 100:
        raise ConfigurationError("BEHAVIOR_AI_DAILY_LIMIT는 1~100으로 설정해 주세요.")
    return values


def load_settings(path):
    values = read_values(path, KEYS)
    for key in ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "SUPABASE_URL"):
        if not values.get(key) or values[key].startswith("YOUR_"):
            raise ConfigurationError(f"{key} 설정이 필요해요. 값은 출력하지 않았어요.")
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


def launch_settings(values, inherited, read_only=False, storage=None, ai=None):
    # Prevent unrelated local Spring/AI settings from overriding the selected development target.
    prefixes = ("SPRING_", "DB_", "SERVER_", "SUPABASE_", "AI_", "OPENAI_", "PHOTO_", "BEHAVIOR_AI_")
    env = {key: value for key, value in inherited.items() if not key.startswith(prefixes)
           and key not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
    env.update(values)
    env.update(DB_MIGRATE="false", SERVER_ADDRESS="127.0.0.1", AI_ENABLED="false", PHOTO_STORAGE_ENABLED="false")
    if storage is not None:
        env.update(storage)
        env["PHOTO_STORAGE_ENABLED"] = "true"
    if ai is not None:
        env.update(ai)
        env["AI_ENABLED"] = "true"
    args = ["--spring.config.location=classpath:/application.properties", "--spring.flyway.enabled=false",
            "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
            "--server.address=127.0.0.1", "--app.ai.enabled=" + env["AI_ENABLED"],
            "--app.photos.enabled=" + env["PHOTO_STORAGE_ENABLED"]]
    if read_only:
        args.append("--spring.datasource.hikari.read-only=true")
    return env, args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=BACKEND / ".env.supabase")
    parser.add_argument("--check-config", action="store_true", help="validate local settings without connecting or printing values")
    parser.add_argument("--read-only", action="store_true", help="make JDBC transactions read-only for connection verification")
    parser.add_argument("--with-photos", action="store_true", help="enable private photos with the separate local server key")
    parser.add_argument("--storage-env-file", type=Path, default=BACKEND / ".env.storage")
    parser.add_argument("--with-ai", action="store_true", help="enable real paid AI calls with the separate local AI key")
    parser.add_argument("--ai-env-file", type=Path, default=BACKEND / ".env.ai")
    args = parser.parse_args()
    try:
        values = load_settings(args.env_file)
        storage = load_storage_settings(args.storage_env_file) if args.with_photos else None
        ai = load_ai_settings(args.ai_env_file) if args.with_ai else None
        if args.check_config:
            print("로컬 연결 설정 형식 확인 완료. 비밀번호·키는 출력하지 않았고 외부에 접속하지 않았어요.")
            return 0
        jar = BACKEND / "build/libs/shelter-connect-api.jar"
        if not jar.is_file():
            raise ConfigurationError("먼저 backend에서 ./gradlew bootJar로 서버를 빌드해 주세요.")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        if not java or not Path(java).is_file():
            raise ConfigurationError("Java 21 경로를 JAVA_HOME에 설정해 주세요.")
        env, flags = launch_settings(values, os.environ, args.read_only, storage, ai)
        print(f"Supabase 개발 연결로 로컬 서버 시작: http://127.0.0.1:{values['PORT']} (읽기 전용: {args.read_only}, 사진: {storage is not None}, AI: {ai is not None})", flush=True)
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
