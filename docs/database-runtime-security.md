# DB 실행 계정·인증서 검증 · B-31

서버는 `shelter_runtime.<project-ref>`로 접속한다. 관리자 `postgres` 계정은 마이그레이션과 개발 검증의 임시 데이터 준비·정리에만 사용한다. 비밀번호를 공유하거나 같은 값으로 맞추지 않는다.

## 권한

V8은 비밀번호 없는 NOLOGIN 역할과 명시적인 테이블 권한·RLS 정책을 만든다. 관리자가 별도 강한 비밀번호로 LOGIN을 활성화한 뒤 서버 환경변수에 등록한다.

- 데이터베이스·역할·테이블 생성, RLS 해제, 권한 위임, TRUNCATE를 허용하지 않는다.
- 사용자 가입은 USER로만 가능하다. 운영자 승격, 비활성 계정 재활성화, 보호소 승인, 담당자 소속 변경은 서버 DB 계정에도 허용하지 않는다.
- 기존 API가 사용하는 테이블의 조회·등록·수정만 허용한다. DELETE는 교체하는 행동 근거 연결에만 허용한다.
- AI 사용량의 시간·사용자를 수정하거나 기록을 삭제할 수 없다. 동시 요청 예약의 해제 시각만 수정할 수 있다.
- `anon`, `authenticated`의 직접 DB 접근 차단은 유지한다. 서버 역할 정책은 애플리케이션의 JWT·소유권 검사와 함께 사용한다.
- Flyway 이력 및 `auth`, `storage`에 권한을 주지 않는다. 다음에 테이블을 추가할 때는 해당 마이그레이션에 필요한 권한과 정책을 명시해야 한다.

## TLS

Render의 DB_URL:

```text
jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&sslrootcert=/app/certs/supabase-prod-ca-2021.crt
```

로컬에서는 `sslrootcert`에 저장소 `backend/certs/supabase-prod-ca-2021.crt`의 절대 경로를 넣는다. 공식 CA는 인증서·서버 이름 검증에 사용하며 공개 정보다. 개인 키가 아니다.

- 출처: Supabase 프로젝트 Database Settings → Download certificate
- [공식 다운로드](https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt)
- SHA-256: `807025AD50D4ED219D2C9C7D299C004F824EB00CF7F65AFEF607D07B72E6CAFA`
- 만료: 2031-04-26. Supabase의 인증서 교체 공지에 맞춰 갱신한다.

서버 시작 전에 사용자명과 연결 옵션을 검사한다. 관리 계정, `sslmode=require`, 인증서를 검증하지 않는 커스텀 SSL factory, 중복·알 수 없는 옵션은 거절한다. 임시 로컬 PostgreSQL 검사용 loopback 연결만 예외다.

## 적용 순서와 확인

1. 선택한 개발 프로젝트에서 관리자 연결로 Flyway V8을 적용한다. 비밀번호는 마이그레이션에 넣지 않는다.
2. 별도 실행 계정의 LOGIN과 비밀번호를 준비한다. 기존 관리자 계정은 마이그레이션용으로 보관한다.
3. 실행 계정으로 인증서·호스트 검증 연결 및 권한 조회를 확인한다.
4. Render의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 함께 교체한다. `DB_MIGRATE=false`를 유지하고 검증한 커밋으로 배포한다.
5. readiness, 공개 조회, 미인증 차단, 실제 로그인·메모·대화 저장과 타인 접근 차단을 확인한다.

로컬 서버는 `python3 backend/scripts/run_supabase.py --env-file backend/.env.runtime`로 실행한다. 로그인·AI 실검증 도구는 관리자 설정을 `--env-file`, 실행 계정 설정을 `--runtime-env-file`로 각각 받는다. 배포 서버 검사(`--api-origin`)는 로컬 서버를 시작하지 않아 runtime 파일을 요구하지 않는다.

문제가 생기면 수정 전 서버 이미지와 환경변수를 함께 복구한다. V8과 기존 데이터는 삭제하지 않는다. 계정을 나눴다고 사용자별 권한 검사를 DB 정책만으로 대체하지 않는다.

[노션 B-31](https://app.notion.com/p/DB-3e95b2d1a55f803798e1f53315c7b9db)
