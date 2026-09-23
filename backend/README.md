# 보호소 커넥트 서버

[Swagger 사용 방법](../docs/swagger-api.md) · [개발 서버 API 명세서](https://shelter-connect-dev.onrender.com/swagger-ui/index.html)

Spring Boot 4.1.1, Java 21, Gradle Wrapper로 시작했어. DB는 Supabase의 PostgreSQL을 쓰고, 로컬에서는 Docker로 PostgreSQL을 띄우면 돼.

서버 실행 설정과 상태 확인, 데이터 구조를 만드는 Flyway 마이그레이션이 들어 있어. [데이터 관계도와 필드 설명](../docs/data-model.md)을 같이 보면 돼. [가상 보호소 2곳·강아지 5마리 샘플](sample-data/README.md), [보호소·강아지 조회 API](../docs/read-api.md), [Supabase 인증과 보호소 권한](../docs/auth-and-permissions.md)도 준비했어. [강아지 등록·수정과 관찰 기록](../docs/dog-management-api.md)도 사용할 수 있어. [대화방·메시지 저장 API](../docs/chat-storage-api.md)도 준비했어. [기록 기반 AI 답변](../docs/grounded-chat-api.md)도 연결했어. AI는 기본 비활성이고 서버 키 설정이 필요해. 지정 개발 DB는 V3까지 적용했어. [사진 조회](../docs/photo-read-api.md)와 [8종 행동 설정](../docs/dog-behavior-api.md)도 구현했어. [개인 입양 준비 메모](../docs/adoption-notes-api.md) 저장·수정·조회도 구현했어. 현재 웹 시안에는 아직 연결하지 않았어.

## 먼저 테스트해보기

Java 21을 설치하고 이 폴더에서 실행해봐. Gradle은 따로 설치할 필요 없어. 처음 실행할 때 필요한 파일을 내려받아.

```sh
./gradlew clean build
```

Windows에서는 `gradlew.bat clean build`를 쓰면 돼. 테스트는 잠깐 쓰고 버리는 메모리 DB(H2)로 실행돼서 Docker나 Supabase 계정이 없어도 돼. 실제 앱에는 H2가 포함되지 않아.

GitHub PR에서는 PostgreSQL 17로 서버 검사와 별도의 `integrationTest`를 실행해. 마이그레이션, 데이터 관계, 중복 저장 차단, 대화 근거의 강아지 일치, 클라이언트 DB 접근 차단과 조회 API의 공개 조건·페이지 이동을 확인해. 인증 검사는 임시 키로 서명한 JWT와 테스트 DB 소속으로 실행하므로 실제 Supabase 계정이 필요하지 않아. 빌드한 JAR도 직접 실행해서 공개 조회와 미인증 요청 차단을 검사해. H2 검사에는 PostgreSQL 마이그레이션이 포함되지 않아.

## 로컬 서버 켜기

Docker가 실행 중인 상태에서, `backend/` 기준으로 진행해줘.

```sh
cp .env.example .env
docker compose up -d --wait db
```

이어서 `.env` 값을 실행 환경에 넣고 서버를 켜면 돼. 아래는 macOS/Linux용 명령이야.

```sh
set -a
source .env
set +a
./gradlew bootRun
```

위 명령은 DB에 접속하고 상태를 확인하는 기본 실행이야. 로컬 DB에 테이블을 만들 준비가 됐다면, 같은 실행 환경에서 `DB_MIGRATE=true ./gradlew bootRun`을 실행해. Flyway가 아직 적용하지 않은 버전만 실행해.

Spring Boot가 `.env`를 자동으로 읽지는 않아. Windows나 IDE에서는 같은 이름의 환경변수를 실행 설정에 넣어줘. 파일 안의 값은 셸 문법이므로, 공백·`$` 같은 문자가 있는 비밀번호는 작은따옴표로 감싸거나 IDE 환경변수 입력란을 쓰면 돼.

다른 터미널에서 확인해봐.

```sh
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/actuator/health/readiness
```

정상이면 readiness에서 `{"status":"UP"}`이 나와. 기본 health 주소에는 상태와 검사 그룹 이름도 함께 나와. 서버와 DB가 준비되지 않았다면 readiness는 503을 반환해. `/actuator/health/liveness`는 서버 자체 상태만 확인해. DB 주소나 내부 설정은 응답에 넣지 않아.

서버는 `Ctrl+C`, 로컬 DB는 `docker compose stop db`로 멈추면 돼. DB 데이터는 Docker 볼륨에 남아.

기본 주소는 내 컴퓨터에서만 접근할 수 있는 `127.0.0.1`이야. 나중에 같은 네트워크의 휴대폰에서 붙일 때는 `SERVER_ADDRESS=0.0.0.0`으로 실행하고, 앱에서 개발 PC의 주소를 사용하면 돼. RN 웹의 CORS 설정은 실제 프론트 주소를 정한 뒤 추가할 예정이야.

## Supabase 연결할 때

Supabase 프로젝트의 **Connect → Direct → Session pooler**에서 호스트·DB 사용자명을 확인해줘. `.env.supabase.example`을 Git에서 제외되는 `.env.supabase`로 복사하고 기존 DB 비밀번호를 넣어. 파일은 셸로 실행하지 않고 `python3 scripts/run_supabase.py`로 읽어. [설정·실행·실제 검증 결과](../docs/supabase-server-connection.md)를 참고해. 로컬 PostgreSQL용 `.env`와는 다른 파일이야.

| 이름 | 용도 |
| --- | --- |
| `SUPABASE_URL` | 필수. 로그인 토큰을 발급하는 프로젝트의 HTTPS 주소. `.env.example`에는 개발 프로젝트의 공개 주소가 들어 있음 |
| `DB_URL` | `jdbc:postgresql://호스트:5432/postgres?sslmode=require` |
| `DB_USERNAME` | Connect 화면의 사용자 이름. 보통 `postgres.프로젝트참조값` |
| `DB_PASSWORD` | DB 비밀번호. Supabase API 키가 아님 |
| `DB_POOL_SIZE` | 연결 수 상한. 기본 5 |
| `PORT` | 서버 포트. 기본 8080 |
| `SERVER_ADDRESS` | 서버가 받을 주소. 기본 `127.0.0.1` |
| `DB_MIGRATE` | Flyway 마이그레이션 실행 여부. 기본 false |

오래 실행되는 Spring 서버 기준으로 세션 풀러의 5432 포트를 사용해. 6543은 트랜잭션 풀러라 이 설정에 섞지 않아. IPv6를 쓸 수 있는 서버라면 direct connection도 가능해. 연결 방식은 [Supabase 공식 안내](https://supabase.com/docs/guides/database/connecting-to-postgres)를 참고하면 돼.

`sslmode=require`는 암호화 연결을 요구해. 인증서 검증까지 필요한 배포 환경에서는 Supabase 인증서를 준비해 `verify-full` 구성을 정하면 돼. 접속 비밀번호와 API 키는 GitHub나 노션에 적지 말고, 환경변수로만 관리해줘.

Hibernate의 자동 테이블 수정은 꺼져 있어(`ddl-auto=validate`). 테이블 변경은 Flyway로 관리하고, `DB_MIGRATE=true`일 때만 적용해. V1은 `shelter` 스키마에 앱 테이블 11개를 만들어. Supabase의 `auth`, `storage`, `public` 테이블은 수정하지 않아.

비어 있는 Supabase 개발 프로젝트에 SQL 편집기로 먼저 구조와 샘플을 넣는 방법도 [샘플 사용 안내](sample-data/README.md#새-supabase-프로젝트에-처음-넣을-때)에 있어. Flyway 이력을 함께 기록하므로 다음 서버 실행에서 V1을 중복 적용하지 않아.

2026.09.21에 지정한 `shelter-connect-dev` 프로젝트에는 구조와 샘플을 적용했고, [실제 DB 확인 결과](../docs/supabase-development-db.md)를 남겼어. B-11에서 Spring Boot의 실제 원격 연결과 readiness·공개 API·미인증 차단을 검증했어. [서버 연결 결과](../docs/supabase-server-connection.md)에 실행 방법을 남겼어. 실제 로그인·개인 기록 저장과 재시작 후 보존은 [B-13](../docs/login-storage-verification.md)에서 확인했어. 외부 배포와 앱 로그인 화면 연결은 다음 작업이야.

## 개발 서버 배포

Render Free 배포 설정은 [개발 서버 배포 안내](../docs/development-deployment.md)에 정리했어. Docker 이미지로 올리고, 기존 Supabase 개발 DB를 연결해. 배포 주소와 실제 검증 결과는 B-14 노션 카드에서 확인해줘. 배포 이미지에는 로컬 비밀번호 파일과 테스트 도구가 들어가지 않아.

## 실제 로그인·저장 확인

[B-13 검증 안내](../docs/login-storage-verification.md)의 별도 명령으로 실제 로그인·대화·메모 저장과 서버 재시작 후 보존을 확인할 수 있어. 지정 개발 프로젝트에 테스트 계정 두 개를 만들고 이번 검사에서 만든 기록만 정리해. 일반 빌드·CI에서는 원격 계정을 만들지 않아.

## 사진 저장소 켜기

사진은 [Supabase Storage 연결 안내](../docs/photo-storage-connection.md)에 따라 `.env.storage`에 서버 키를 넣고 `python3 scripts/run_supabase.py --with-photos`로 켤 수 있어. 처음 저장소 준비와 실제 이미지 다운로드 검사는 문서의 별도 명령을 사용해. 테스트 이미지와 실제 강아지 사진 등록은 구분해뒀어.

B-12에서 지정 개발 프로젝트에 비공개 `dog-photos`를 만들고 서버 코드의 서명 URL 발급·실제 PNG 다운로드·공개 접근 차단까지 확인했어. 실제 보호소 사진과 로그인 사용자의 사진 조회 흐름은 다음 작업이야.

## AI 답변 켜기

로컬에서는 `.env.ai.example`을 Git에서 제외되는 `.env.ai`로 복사하고 키를 넣은 다음 `python3 scripts/run_supabase.py --with-ai`로 켜. 옵션이 없으면 AI는 꺼져 있어. 배포 환경에서는 서버에만 `OPENAI_API_KEY`와 `AI_ENABLED=true`를 설정해. 기본 모델은 `OPENAI_MODEL=gpt-5.6-luna`, 호출 제한은 `AI_TIMEOUT_SECONDS=30`이야. V2 이상 DB가 필요하고 지정 Supabase 개발 프로젝트는 V3까지 적용했어. [답변 생성·재시도](../docs/grounded-chat-api.md)와 [실제 호출 검증 방법](../docs/live-ai-verification.md)을 같이 읽어줘.

## 폴더와 검사 범위

- `src/main/java/org/shelterconnect/api`: 서버 시작 코드와 `catalog` 조회, `auth` 인증·권한, `management` 강아지·관찰 관리, `chat` 본인 대화 저장, `web` 공통 요청 처리.
- `src/main/resources/application.properties`: 공통 서버·DB·상태 확인 설정.
- `src/test`: HTTP 상태 확인, 내부 관리 API 비공개, 준비 상태 전환, DB 조회 검사.
- `sample-data`: 가상 보호소·강아지·관찰 기록 원본 JSON과 화면 확인 예시.
- `src/sample`: `./gradlew loadSampleData`로만 실행하는 로컬 샘플 로더. 서버 JAR에는 포함하지 않아.
- `src/main/resources/db/migration`: 버전별 PostgreSQL 구조. 적용한 파일은 수정하지 않고 다음 버전을 추가해.
- `compose.yaml`: 로컬 PostgreSQL. 포트는 `15432`, 데이터는 전용 볼륨에 보관해.
- `../.github/workflows/backend.yml`: PR과 main의 자동 테스트·빌드·JAR 실행 검사.

실제 PostgreSQL로 서버 검사를 하려면 `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`, `TEST_DB_DRIVER=org.postgresql.Driver`를 지정하고 `./gradlew test`를 실행해.

마이그레이션 검사는 `./gradlew integrationTest`로 따로 실행해. 로컬 호스트의 `shelter_test` DB만 허용하고, 테스트 역할을 만들 수 있는 임시 DB 관리자 계정을 사용해. 접속 변수가 없으면 실패하므로, DB 검사 없이 통과한 것으로 오해하지 않게 했어.

로컬 Docker DB가 실행 중이고 `.env`를 불러온 상태라면 테스트 DB를 이렇게 준비할 수 있어. DB 생성은 최초 한 번만 하면 돼.

```sh
docker compose exec db createdb -U "$DB_USERNAME" shelter_test
TEST_DB_URL='jdbc:postgresql://127.0.0.1:15432/shelter_test' \
TEST_DB_USERNAME="$DB_USERNAME" TEST_DB_PASSWORD="$DB_PASSWORD" \
./gradlew integrationTest
```

진행 순서와 PR 기록 방식은 [백엔드 진행 규칙](../docs/backend-workflow.md)에 있어. B-07 기록 기반 AI 연결까지 구현했어. 저장한 사용자 메시지에 답변을 요청하면 확인된 관찰을 바탕으로 생성하고, 근거와 처리 상태를 함께 저장해. 실제 키 호출·가상 질문 11개의 답변·근거 검토는 [Q-02](../docs/live-ai-verification.md)에서 확인했고, 지정 개발 DB의 V2/V3는 적용했어. C-02의 로그인 화면 흐름과 C-03의 프론트 검토, 실제 앱 연결은 따로 확인하고, B-10 강아지 행동 설정도 구현했어. 구현 상태와 실제 DB 적용·앱 연결 상태는 각 문서에서 구분해 두었어.

사진은 [B-08 사진 조회](../docs/photo-read-api.md)를 참고해. 본인의 해당 강아지 답변 1회 후 허가된 사진을 조회하고, 비공개 Storage URL은 60초 동안 유효해. 비공개 버킷·서버 키와 실제 서명 URL 다운로드는 B-12에서 확인했어. 실제 보호소 사진 등록은 사용 허가를 받을 때까지 보류해.

행동 설정은 [B-10 연결 문서](../docs/dog-behavior-api.md)를 보면 돼. GET으로 읽고 PUT으로 초안을 저장한 뒤 confirmation으로 확인해. V3의 수정 버전과 관찰 근거 테이블은 지정 Supabase 개발 DB에도 적용했어. AI 호출 없이 설정을 읽을 수 있고, 미확인 설정은 기본 대기·걷기를 반환해.

2026.09.21 V2/V3 원격 적용과 사후 검증을 마쳤어. [적용 결과와 일회성 내보내기](../docs/supabase-development-db.md)를 참고해. RN 애니메이션 연결은 프론트의 캐릭터·동작 재생 구조가 준비된 뒤 진행하면 돼.

입양 준비 메모는 [B-09 연결 문서](../docs/adoption-notes-api.md)를 참고해. 사용자·강아지별로 질문, 돌봄 계획, 준비 체크를 저장하고 본인만 조회·수정해. 수정할 때는 응답의 `updatedAt`을 그대로 보내 오래된 내용의 덮어쓰기를 막아. 기존 V1 테이블을 사용하므로 추가 DB 적용이나 새 환경변수는 없어.

로컬에서 Supabase 연결만 확인할 때는 `python3 scripts/run_supabase.py --read-only`를 사용해. 이 도구는 마이그레이션·AI·Storage를 끄고 로컬 주소에서 서버를 실행해. 비밀번호가 담긴 `.env.supabase`는 Git에서 제외되고 명령행 인자로 전달되지 않아.

사진과 특징 등록은 [입력 흐름 명세서](../docs/asset-input-workflow.md)를 따라 연결하면 돼.
