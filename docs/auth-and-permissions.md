# 인증과 보호소 권한

B-04에서는 Supabase Auth의 로그인 정보를 확인하고, 그 사용자가 어느 보호소의 동물을 관리할 수 있는지 판단하는 서버 기능을 넣었어. 보호소·강아지 공개 목록은 그대로 로그인 없이 볼 수 있어.

실제 동물 등록·수정 API는 B-05에서 추가해. 이번 `/access` API는 관리 화면에 들어갈 수 있는지 확인하는 용도이고, 이후 등록·수정 요청에서도 서버가 권한을 다시 검사해야 해.

## 앱에서 연결할 순서

1. Supabase Auth로 로그인해서 사용자 `access_token`을 받아.
2. `Authorization: Bearer <access_token>` 헤더로 `POST /v1/me`를 호출해. 처음이면 앱 사용자를 만들고, 이미 등록돼 있으면 같은 사용자를 돌려줘. 요청 본문은 보내지 않아도 돼.
3. `GET /v1/me`로 내 정보를, `GET /v1/me/shelters`로 내가 관리할 수 있는 보호소 목록을 받아.
4. 관리 화면이 필요하면 아래 보호소·강아지 접근 확인 API를 사용해. 목록이나 버튼이 보였다는 사실만으로 이후 수정 권한이 유지되는 건 아니야.

소셜 로그인 종류와 앱에서 로그인을 요청할 시점은 C-02에서 정할 내용이야. 프론트가 비밀번호나 refresh token을 Spring 서버로 보내지는 않아. 세션 저장·토큰 갱신·로그아웃은 Supabase 클라이언트 쪽에서 처리하고, Spring에는 현재 access token만 보내면 돼.

## API

모든 성공 응답은 `data`로 감싸. 아래 다섯 주소는 유효한 사용자 토큰이 필요해.

| 메서드·주소 | 동작 | 응답 data |
| --- | --- | --- |
| `POST /v1/me` | 내 서비스 사용자 등록. 반복 호출해도 중복 생성하지 않음 | `id`, `displayName`, `role` |
| `GET /v1/me` | 등록된 내 정보 | `id`, `displayName`, `role` |
| `GET /v1/me/shelters` | 내가 관리할 수 있는 승인 보호소 | `[{shelterId, name, memberRole}]` |
| `GET /v1/shelter-admin/shelters/{shelterId}/access` | 해당 보호소 관리 권한 확인 | `shelterId`, `dogId: null`, `memberRole` |
| `GET /v1/shelter-admin/dogs/{dogId}/access` | 해당 강아지가 실제 소속된 보호소의 관리 권한 확인 | `shelterId`, `dogId`, `memberRole` |

`POST /v1/me`는 처음 등록과 재호출 모두 **200**이야. 새 사용자는 이름 `방문자`, 역할 `USER`로 만들고 보호소 소속은 부여하지 않아. `userId`, `role`, `shelterId`를 본문이나 쿼리에 보내도 등록·권한에 사용하지 않아. 이 API는 이름이나 역할 수정 기능도 아니야.

`data.id`는 서비스의 `app_users.id`야. Supabase Auth의 사용자 ID와는 별개로 생성해. 프론트가 이 ID를 다음 요청의 로그인 증명으로 보내면 안 돼. 항상 토큰에서 검증한 사용자만 사용해.

예를 들어 온기 보호소 담당자의 소속 조회 결과는 이런 모양이야. 아래 ID는 가상 샘플이고, 샘플 사용자에는 실제 로그인 계정이 연결돼 있지 않아.

```json
{
  "data": [
    {
      "shelterId": "02100000-0000-4000-8000-000000000001",
      "name": "온기 보호소 (가상)",
      "memberRole": "MANAGER"
    }
  ]
}
```

소속이 없는 일반 사용자는 `{"data":[]}`를 받아. 목록은 보호소 ID 순서이고, 현재 본인 소속 조회에는 페이지 구분이 없어. 다른 사용자의 소속 조회 API도 제공하지 않아.

## 권한 기준

| 상태 | 내 정보 | 보호소·동물 관리 접근 |
| --- | --- | --- |
| 로그인하지 않음 / 잘못된 토큰 | 401 | 401 |
| 로그인했지만 서비스 사용자 등록 전 | 403 `ACCOUNT_NOT_REGISTERED` | 동일 |
| 등록된 일반 사용자, 보호소 소속 없음 | 가능 | 403 |
| `ACTIVE` 소속의 `MANAGER` 또는 `STAFF` + `APPROVED` 보호소 | 가능 | 자기 보호소와 그 보호소의 동물만 가능 |
| `INVITED` / `REVOKED` 소속 | 가능 | 403 |
| `PENDING` / `REJECTED` / `SUSPENDED` 보호소 | 가능 | 403 |
| 사용 중지된 계정 (`disabled_at` 있음) | 403 `ACCOUNT_DISABLED` | 동일 |
| `OPERATOR` 역할만 있고 보호소 소속 없음 | 가능 | 403 |

운영자 역할에도 모든 보호소를 관리하는 우회 권한을 주지 않았어. 보호소 승인·담당자 초대·권한 부여 API는 이번 범위에 없어. 실제 담당자를 연결할 때는 운영자가 신원을 확인한 뒤 서버 DB의 소속과 승인 정보를 관리해야 해. 토큰의 `user_metadata`, `app_metadata`에 역할이나 보호소 ID가 있어도 앱 권한으로 사용하지 않아.

공개 여부와 관리 권한은 별개야. 승인된 보호소의 활성 담당자는 비공개 보호소나 입양 중지·비공개 강아지도 관리 대상인지 확인할 수 있어. 공개 조회에서 보여주는 조건은 [조회 API 문서](read-api.md)를 따라.

대상 보호소·강아지가 없거나 내 소속이 아니면 둘 다 403 `FORBIDDEN`이야. 다른 보호소의 내부 데이터 존재 여부를 구분해서 알려주지 않아. 잘못된 UUID는 400이야.

## 오류와 토큰 처리

```json
{
  "code": "UNAUTHENTICATED",
  "message": "로그인이 필요하거나 로그인 정보가 만료됐어요.",
  "requestId": "서버가 생성한 UUID"
}
```

| HTTP / code | 처리 |
| --- | --- |
| 401 `UNAUTHENTICATED` | 토큰 누락·위조·만료 등. 세션을 확인하고 필요하면 갱신하거나 로그인 |
| 403 `ACCOUNT_NOT_REGISTERED` | `POST /v1/me`로 등록 후 다시 조회 |
| 403 `ACCOUNT_DISABLED` | 사용 중지된 계정. 등록 재호출로 복구되지 않음 |
| 403 `FORBIDDEN` | 소속·보호소 승인·동물 소속 조건에 맞지 않음. 토큰을 갱신해도 권한이 생기지 않음 |
| 400 `INVALID_REQUEST` | 잘못된 ID 형식 |
| 500 `INTERNAL_ERROR` | 서버 처리 실패. 내부 DB 정보는 응답에 포함하지 않음 |

401에는 `WWW-Authenticate: Bearer`가 있어. 인증·권한 오류에도 `X-Request-ID`와 `Cache-Control: no-store`가 붙어. 서버 세션이나 로그인 쿠키는 만들지 않고, 쿠키·URL 쿼리로 토큰을 받지 않아. Bearer 토큰은 로그, 주소창, PR에 남기지 말아줘.

공개 API에 토큰을 **보내지 않으면** 기존처럼 조회할 수 있어. 잘못된 토큰을 첨부하면 공개 API라도 401이야. 앱에서 오래된 토큰을 자동으로 붙이고 있다면 이 차이를 처리해줘.

이번에 열어둔 경로·메서드 외에는 기본 차단이야. 상태 확인은 계속 공개하고, 보호소·강아지 등록·수정 주소는 아직 열지 않았어. RN 웹의 CORS 허용 주소는 실제 프론트 주소가 정해진 뒤 추가해.

## 서버 설정과 내부 식별

필수 환경변수는 `SUPABASE_URL`이야. 예: `https://gwimdiwrqfcqulefshoz.supabase.co`. API 키나 JWT secret이 아니라 프로젝트의 공개 주소이고, HTTPS 원점만 허용해. 값이 없거나 주소 형식이 잘못되면 서버 시작을 실패시켜.

- issuer: `${SUPABASE_URL}/auth/v1`
- 공개 서명 키: `${SUPABASE_URL}/auth/v1/.well-known/jwks.json`
- 서명 알고리즘: **ES256**. 현재 프로젝트의 공개 JWKS에서 확인한 방식이며 키 ID는 고정하지 않아.
- 사용자 조건: UUID `sub`, `aud`에 `authenticated`, `role=authenticated`, `is_anonymous=false`, 유효한 발급·만료 시각.
- 시간 검증에는 Spring 기본 60초 오차 허용을 사용해. HS256, 익명 사용자, anon/service_role 키는 받지 않아.

서명 검증은 Spring Security의 Nimbus JWT 검증기를 사용해. 공개 키는 라이브러리에서 캐시하고, HTTP 연결·읽기 제한은 각각 3초야. JWT secret이나 Supabase service-role 키를 Spring 설정에 넣을 필요는 없어. 단, PostgreSQL 접속을 위한 DB 환경변수는 기존대로 필요해.

`auth_provider`에는 `sb:`와 issuer의 SHA-256 앞 16바이트를 32자리 16진수로 저장해. `auth_subject`에는 검증된 Supabase 사용자 UUID를 소문자 표준 형식으로 저장해. 같은 UUID라도 다른 프로젝트의 사용자와 연결되지 않도록 한 거야. 예전 샘플의 빈 인증 식별자를 자동으로 채우거나 이름·이메일로 기존 담당자와 연결하지 않아.

Supabase 로그아웃이나 Auth 쪽 계정 상태 변경이 이미 발급된 JWT를 즉시 무효화하는 것은 아니야. 해당 토큰은 만료 전까지 검증을 통과할 수 있어. 서비스에서 즉시 차단해야 할 때는 `app_users.disabled_at`, 소속 `REVOKED`, 보호소 `SUSPENDED`를 사용해. 이 상태는 요청마다 DB에서 다시 확인해.

V1 마이그레이션과 RLS는 변경하지 않았어. 실제 Supabase 계정 생성, 샘플 계정 연결, 담당자 권한 부여, 로그인 제공자 설정, 운영 DB 권한 변경도 이번 작업에서는 하지 않았어.

## 다음 작업에서 지켜야 할 점

B-05 등록·수정 서비스는 검증된 JWT subject로 `ShelterAccessService.requireShelter` 또는 `requireDog`를 호출해야 해. 권한 확인을 실제 변경 트랜잭션 안에서 실행하고, 동물의 소속은 DB에서 읽어. 앞서 받았던 `/access` 응답을 수정 권한의 증명으로 받으면 안 돼. 동시 소속 변경과 수정의 경합은 B-05에서 잠금이나 조건부 변경과 함께 검증해야 해.

B-06 대화·B-09 메모는 `app_users.id`로 소유권을 확인할 예정이야. 토큰이 유효하다는 것만으로 다른 사용자의 대화나 메모 접근을 허용하면 안 돼. 이 API들은 아직 구현하지 않았어.

## 검증 범위

테스트는 실행할 때 만든 임시 ES256 키로 실제 JWT를 서명하고, JWKS HTTP 응답만 로컬에서 대체해. 서명 검증을 건너뛰는 가짜 로그인으로 통과시키지 않아. 위조 서명·다른 알고리즘·만료·issuer/audience 오류·익명 사용자 차단, 등록 중복 방지, 실제 PostgreSQL의 소속·승인·타 보호소 차단을 확인해. CI에서는 빌드한 JAR의 공개 조회와 미인증 차단도 별도로 확인해.

실제 Supabase 로그인 계정으로 받은 토큰과 원격 DB를 연결한 전체 실행, 앱 연결·배포는 아직 확인하지 않았어. C-02의 소셜 로그인·사진 공개 조건과 C-03의 프론트 합의도 별도로 남아 있어.

구현 기준은 [Supabase JWT 안내](https://supabase.com/docs/guides/auth/jwts)와 [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)를 참고했어.
