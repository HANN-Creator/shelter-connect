# 보호소 커넥트 서버

Spring Boot 4.1.1, Java 21, Gradle Wrapper로 시작했어. DB는 Supabase의 PostgreSQL을 쓰고, 로컬에서는 Docker로 PostgreSQL을 띄우면 돼.

서버 실행 설정과 상태 확인, 데이터 구조를 만드는 Flyway 마이그레이션이 들어 있어. [데이터 관계도와 필드 설명](../docs/data-model.md)을 같이 보면 돼. [가상 보호소 2곳·강아지 5마리 샘플](sample-data/README.md)도 준비했어. 보호소·강아지·대화 API와 인증은 다음 작업이야. 현재 웹 시안에는 아직 연결하지 않았어.

## 먼저 테스트해보기

Java 21을 설치하고 이 폴더에서 실행해봐. Gradle은 따로 설치할 필요 없어. 처음 실행할 때 필요한 파일을 내려받아.

```sh
./gradlew clean build
```

Windows에서는 `gradlew.bat clean build`를 쓰면 돼. 테스트는 잠깐 쓰고 버리는 메모리 DB(H2)로 실행돼서 Docker나 Supabase 계정이 없어도 돼. 실제 앱에는 H2가 포함되지 않아.

GitHub PR에서는 PostgreSQL 17로 서버 검사와 별도의 `integrationTest`를 실행해. 마이그레이션, 데이터 관계, 중복 저장 차단, 대화 근거의 강아지 일치, 클라이언트 DB 접근 차단을 확인하고, 빌드한 JAR도 직접 실행해. H2 검사에는 PostgreSQL 마이그레이션이 포함되지 않아.

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

Supabase 프로젝트의 **Connect → Session pooler**에서 호스트·DB 사용자·비밀번호를 확인해줘. `.env.supabase.example`을 참고해서 별도 로컬 환경 파일이나 IDE 실행 환경에 넣으면 돼. 로컬 DB용 `.env`와 구분하면 실수하기 어려워.

| 이름 | 용도 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://호스트:5432/postgres?sslmode=require` |
| `DB_USERNAME` | Connect 화면의 사용자 이름. 보통 `postgres.프로젝트참조값` |
| `DB_PASSWORD` | DB 비밀번호. Supabase API 키가 아님 |
| `DB_POOL_SIZE` | 연결 수 상한. 기본 5 |
| `PORT` | 서버 포트. 기본 8080 |
| `SERVER_ADDRESS` | 서버가 받을 주소. 기본 `127.0.0.1` |
| `DB_MIGRATE` | Flyway 마이그레이션 실행 여부. 기본 false |

오래 실행되는 Spring 서버 기준으로 세션 풀러의 5432 포트를 사용해. 6543은 트랜잭션 풀러라 이 설정에 섞지 않아. IPv6를 쓸 수 있는 서버라면 direct connection도 가능해. 연결 방식은 [Supabase 공식 안내](https://supabase.com/docs/guides/database/connecting-to-postgres)를 참고하면 돼.

`sslmode=require`는 암호화 연결을 요구해. 인증서 검증까지 필요한 배포 환경에서는 Supabase 인증서를 준비해 `verify-full` 구성을 정하면 돼. 접속 비밀번호와 API 키는 GitHub나 노션에 적지 말고, 환경변수로만 관리해줘.

Hibernate의 자동 테이블 수정은 꺼져 있어(`ddl-auto=validate`). 테이블 변경은 Flyway로 관리하고, `DB_MIGRATE=true`일 때만 적용해. V1은 `shelter` 스키마에 테이블 11개를 만들어. 실제 Supabase 프로젝트 접속과 운영 DB 적용은 아직 확인하지 않았어. Supabase의 `auth`, `storage`, `public` 테이블은 수정하지 않아.

## 폴더와 검사 범위

- `src/main/java/org/shelterconnect/api`: 서버 시작 코드. 기능 코드는 여기에 추가할 예정이야.
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

진행 순서와 PR 기록 방식은 [백엔드 진행 규칙](../docs/backend-workflow.md)에 있어. B-02의 샘플 로딩까지 준비했고, 다음은 C-03의 응답 합의를 맞춰 B-03 조회 API를 만드는 순서야.
