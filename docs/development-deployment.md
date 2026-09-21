# 개발 서버 배포

B-14에서는 Render Free 웹 서버 한 개와 기존 Supabase 개발 DB를 연결해. 새 DB를 만들거나 유료 서버로 바꾸지 않아. 배포 주소와 실제 확인 결과는 [B-14 작업 카드](https://app.notion.com/p/3e25b2d1a55f808b8d14d514e6191d76)에 기록해. 설정 병합과 외부 배포 완료는 구분해서 봐줘.

## 서버 구성

| 항목 | 설정 |
| --- | --- |
| 서비스 이름 | `shelter-connect-dev` |
| 방식 | Docker · Java 21 · 관리자 권한 없이 실행 |
| 리전 / 요금제 | Singapore / Free |
| 저장소 / 브랜치 | `HANN-Creator/shelter-connect` / `main` |
| Dockerfile / 빌드 기준 폴더 | `backend/Dockerfile` / `backend` |
| 상태 확인 | `/actuator/health/readiness` |
| 배포 시점 | PR·main CI 확인 후 수동 배포 |
| DB 변경 | 없음. `DB_MIGRATE=false`, 기존 V3 구조 검증만 수행 |

[render.yaml](../render.yaml)에 같은 설정이 있어. Blueprint로 처음 연결하거나, 공개 Git 저장소를 선택해 위 값을 직접 입력할 수 있어. 공개 저장소 방식은 GitHub의 다른 비공개 저장소 권한을 추가할 필요가 없어. 직접 만든 서비스에는 YAML 변경이 자동 반영되지 않으니 대시보드 설정도 함께 확인해줘.

Render가 지정한 `PORT`를 받고 `0.0.0.0`에서 요청을 받아. 컨테이너는 Java 힙에 가용 메모리의 최대 50%를 사용해. 나머지는 클래스 정보, 스레드, 네트워크 등에 필요해. Docker 빌드에는 허용한 서버 소스만 전달하고, 최종 이미지에는 JRE와 실행 JAR만 넣어. `.env` 파일, 샘플 데이터 로더, 테스트 도구는 배포 이미지에 들어가지 않아.

개발 서버에는 `TieredStopAtLevel=1`도 적용해. 아주 작은 CPU에서 시작할 때 컴파일에 쓰는 시간을 줄이는 설정이야. 높은 처리량을 위한 최적화는 줄어드니, 나중에 유료 서버에서 부하를 측정할 때 다시 비교하면 돼.

## 환경변수 넣기

서비스의 Environment에 아래 이름으로 넣어줘. 로컬 `.env` 파일을 GitHub에 올리거나, Docker 빌드 인자로 비밀번호를 넘기면 안 돼. 비밀 값은 PR·노션·로그에도 적지 않아.

| 이름 | 넣을 값 |
| --- | --- |
| `SUPABASE_URL` | 기존 개발 프로젝트의 HTTPS 주소 |
| `DB_URL` | 기존 session pooler의 JDBC 주소. `:5432/postgres?sslmode=require` |
| `DB_USERNAME` | 기존 개발 DB 사용자 이름 |
| `DB_PASSWORD` | 기존 개발 DB 비밀번호 |
| `DB_POOL_SIZE` | `3` |
| `DB_MIGRATE` | `false` |
| `SERVER_ADDRESS` | `0.0.0.0` |
| `AI_ENABLED` | 첫 배포 확인은 `false` |
| `PHOTO_STORAGE_ENABLED` | 첫 배포 확인은 `false` |

로컬에서 쓰던 `PORT=8080`은 복사하지 않아도 돼. Render가 정한 포트를 그대로 사용해.

AI를 켤 때는 서버에 `OPENAI_API_KEY`, `OPENAI_MODEL=gpt-5.6-luna`, `AI_TIMEOUT_SECONDS=30`, `AI_ENABLED=true`를 넣어. Render 서버가 무료여도 실제 AI 호출 요금은 따로 발생해. 공개 개발 서버에는 아직 사용자별 호출 횟수 제한이 없으므로, 개발자 검증 후 넓게 공유하기 전에 제한을 추가해야 해. 키를 RN 앱에 넣지 않아.

사진 기능은 `SUPABASE_SECRET_KEY`, `PHOTO_STORAGE_BUCKET=dog-photos`, `PHOTO_STORAGE_TIMEOUT_SECONDS=5`, `PHOTO_STORAGE_ENABLED=true`로 켤 수 있어. 현재 실제 강아지 사진은 등록하지 않았고, 사용 허가를 받을 때까지 등록을 보류해.

## 배포 후 확인

Render에서 배포가 Live이고, 실행 중인 커밋이 검사한 `main` SHA와 일치하는지 먼저 봐줘. 첫 요청은 서버가 깨어나는 시간을 포함할 수 있어. 아래의 `실제-서비스-주소`는 Render에 표시된 주소로 바꿔.

```sh
curl --fail https://실제-서비스-주소.onrender.com/actuator/health/readiness
```

`{"status":"UP"}`을 확인한 뒤, `backend/`에서 가상 데이터 조회와 미인증 차단을 검사해. 이 두 명령은 실제 로그인 토큰을 사용하지 않고 데이터를 저장하지 않아.

```sh
API_BASE_URL=https://실제-서비스-주소.onrender.com python3 scripts/check_read_api.py
API_BASE_URL=https://실제-서비스-주소.onrender.com python3 scripts/check_auth_api.py
```

실제 로그인·대화·입양 준비 메모는 다음의 별도 검사로 확인해. 지정한 개발 Supabase 프로젝트에 임시 일반 계정 2개를 만들고, 새 로그인 후 저장 내용·타인 접근 차단·중복 요청·수정 충돌을 확인해. 마지막에 이번 실행의 계정과 기록만 지우고 기존 12개 테이블 건수를 비교해. 실행 중에는 다른 개발자가 같은 DB에 쓰는 작업을 잠시 피하면 좋아.

```sh
python3 scripts/check_login_storage.py \
  --project-ref gwimdiwrqfcqulefshoz \
  --api-origin https://실제-서비스-주소.onrender.com
```

설정 파일이 다른 폴더에 있으면 `--env-file`과 `--storage-env-file`로 경로를 넘겨. 원격 검사에는 HTTPS Render 기본 주소만 허용하고 리다이렉트를 따라가지 않아. Supabase 서버 키와 DB 비밀번호는 Supabase 연결에만 쓰고, 배포 서버에는 임시 일반 계정의 JWT만 보낸다. 이 명령은 배포 서버를 재시작하지 않고 AI나 사진 저장소를 호출하지 않아. 재배포 전후 보존이나 실제 AI 답변을 확인했다면 별도 결과로 적어줘.

검사가 중단되면 `backend/build/login-check-*/created-users.txt`에 이번 실행의 복구 정보가 남아. 이름 패턴으로 다른 계정까지 지우지 말고, 기록된 UUID와 실행 표시가 일치하는 계정만 확인해.

## 다음 변경 배포와 복구

1. 작업 브랜치의 PR을 검사하고 `main`에 병합해. `main` CI도 통과했는지 확인해.
2. Render의 Manual Deploy에서 검사한 커밋을 선택해. 배포 이벤트에 표시된 SHA를 확인해.
3. readiness·공개 조회·인증 경계를 확인하고, 저장 기능이 바뀌었다면 로그인 저장 검사도 실행해.
4. 문제가 생기면 Events에서 직전 정상 배포로 Rollback해. 다시 readiness와 조회를 확인하고, 원인과 되돌린 버전을 노션에 적어.

롤백은 서버 코드에만 적용돼. DB 변경을 되돌리는 기능은 아니야. 이번 배포는 마이그레이션을 실행하지 않으므로 기존 DB 구조를 그대로 사용해.

## 무료 서버에서 알아둘 점

[Render 무료 플랜](https://render.com/docs/free)은 15분 동안 요청이 없으면 서버를 쉬게 하고, 다음 요청에 다시 켜. 첫 응답이 약 1분 늦을 수 있어. 서버 파일은 재배포·재시작 때 사라질 수 있으니 기록은 Supabase DB/Storage에 저장해. 무료 시간은 워크스페이스의 다른 무료 서비스와 합산돼.

유료 업그레이드나 결제 카드 등록은 하지 않아. 현재 무료 한도를 넘으면 서비스를 계속 무료로 쓸 수 있는지 확인하고, 유료 전환이 필요하면 먼저 결정해. 서버를 계속 깨우는 외부 핑은 설정하지 않아.

RN 앱은 이 HTTPS 주소를 API 기본 주소로 쓰면 돼. RN 웹은 프론트 주소를 정한 뒤 해당 주소만 허용하는 CORS 설정을 추가해야 해.
