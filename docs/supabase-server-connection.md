# Spring Boot와 Supabase 개발 DB 연결 · B-11

2026.09.21에 로컬 Spring Boot 서버를 지정한 `shelter-connect-dev` 개발 프로젝트에 실제로 연결했어. 대시보드 SQL 편집기에서만 확인한 게 아니라, Java의 PostgreSQL 연결로 기존 데이터를 읽고 서버의 HTTP API까지 확인했어.

## 확인한 결과

| 항목 | 결과 |
| --- | --- |
| 프로젝트 | `gwimdiwrqfcqulefshoz` / shelter-connect-dev |
| 연결 방식 | 대시보드에서 확인한 Session pooler, 5432, SSL 필요 |
| 서버 준비 상태 | `/actuator/health/readiness` → 200, `{"status":"UP"}` |
| 보호소 | 온기·다온 2곳, 지역 필터·페이지 이동 확인 |
| 공개 강아지 | 봄이·두부·콩이·밤이 조회, 온기 3마리·다온 1마리 |
| 비공개 강아지 | 해리 프로필과 행동 조회 404 |
| 프로필 | 생일 정밀도·추정 여부, 실제 사진 제외 확인 |
| 행동 설정 | 미확인 상태의 기본 8종 설정, 공 추적 비활성 확인 |
| 미인증 요청 | 메모·대화·관리·사진 등을 포함한 30개 경로 401 |

개발 DB에는 강아지 5마리가 있지만 공개 조회에는 4마리만 보여. 해리는 비공개 처리 확인용이야. 이는 목록 누락이 아니라 기존 공개 조건에 따른 결과야.

검증할 때는 JDBC 연결을 읽기 전용으로 열고 기존 공개 조회·미인증 검사만 실행했어. 마이그레이션·SQL 초기화·AI·Storage 호출은 끄고 확인용 서버는 검사 후 종료했어. DB 비밀번호·데이터·RLS·계정 권한을 변경하지 않았어. 실제 로그인 토큰을 사용하는 저장 검증, AI·사진 연결, 외부 배포와 RN 연결은 이후 작업이야.

## 다시 실행하기

Java 21과 Python 3를 준비하고 `backend/`에서 실행하면 돼. Gradle Wrapper를 사용하므로 Gradle은 별도로 설치하지 않아도 돼.

처음 설정할 때만 예제 파일을 복사해. 기존 파일이 있으면 덮어쓰지 않아.

```sh
cp -n .env.supabase.example .env.supabase
chmod 600 .env.supabase
```

Supabase의 Connect → Direct → Session pooler에서 호스트·사용자명을 확인해서 `.env.supabase`에 넣어줘. DB 비밀번호는 프로젝트를 만들 때 정한 값을 `DB_PASSWORD=` 뒤에 넣어. 이 파일은 Git에서 제외돼. 공개 주소·사용자명은 비밀번호가 아니지만, 연결 파일 전체를 GitHub나 노션에 붙여 넣지는 않아.

값에 셸용 따옴표를 덧붙이지 말고 그대로 적어. 비밀번호 안의 `$`, `#`, 작은따옴표, 백틱은 문자 그대로 사용해. 비밀번호의 앞뒤 공백도 보존하므로 의도하지 않은 공백이 들어가지 않게 해줘. 이 파일은 `source`로 실행하지 않아.

```sh
python3 scripts/run_supabase.py --check-config
./gradlew bootJar
python3 scripts/run_supabase.py --read-only
```

`--check-config`는 설정 형식만 확인하고 비밀번호를 출력하거나 DB에 접속하지 않아. 실제 비밀번호가 맞는지는 서버 실행으로 확인해. `--read-only`는 이번처럼 조회 연결을 검증할 때 사용해. 인증된 저장 기능을 확인하는 후속 작업에서는 이 옵션을 빼고 실행해야 해.

다른 터미널에서 현재 샘플 데이터 기준으로 검사할 수 있어.

```sh
curl http://127.0.0.1:8080/actuator/health/readiness
python3 scripts/check_read_api.py
python3 scripts/check_auth_api.py
```

기존 샘플 공개 상태나 이름을 바꾸면 `check_read_api.py`의 기대값도 함께 조정해야 해. 검사 스크립트가 샘플 데이터를 다시 넣거나 고쳐주지는 않아.

서버는 실행한 터미널에서 `Ctrl+C`로 종료해. 기본 주소는 `127.0.0.1:8080`이라 이 컴퓨터에서만 열 수 있어. 다른 서버가 8080을 사용하고 있으면 연결 설정의 `PORT`를 바꾸되, 현재 검사 스크립트는 8080 기준이라는 점을 확인해줘.

## 실행 도구가 고정하는 설정

`run_supabase.py`는 해당 설정 파일만 읽고 선택한 프로젝트의 세션 풀러 주소·프로젝트와 일치하는 사용자명·SSL 설정을 확인해. 비밀번호를 명령행 인자로 넘기거나 셸로 해석하지 않아. 관계없는 기존 Spring·DB·AI 설정과 Java 추가 옵션은 자식 프로세스에 넘기지 않고, Java 경로·일반 실행 환경은 유지해.

- 로컬 주소 `127.0.0.1`, 마이그레이션과 SQL 초기화 비활성
- Hibernate 자동 변경 비활성(`validate`)
- AI와 Storage 호출 비활성
- 연결 풀 기본 3개, 최대 5개

현재는 세션 풀러의 `postgres.<project-ref>` 사용자와 `sslmode=require`를 쓰는 개발 연결 도구야. 직접 연결·사용자 지정 도메인·운영 DB 역할·인증서 검증 강화 설정은 이 도구의 지원 범위가 아니야. 운영 배포나 실제 AI·Storage 실행에는 목적에 맞는 서버 환경 설정을 따로 사용하면 돼.

원격 DB에는 테스트용 데이터 생성·삭제를 하는 `integrationTest`나 샘플 로더를 실행하지 않아. 기존 테스트 DB 검사는 로컬의 임시 `shelter_test`만 허용하고, 원격 연결 검증은 위 HTTP 조회로 진행했어.
