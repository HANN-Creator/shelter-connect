# 강아지 등록·수정과 관찰 기록

B-05는 보호소 담당자가 자기 보호소의 강아지를 관리하는 API야. [B-04 인증 흐름](auth-and-permissions.md)으로 연결한 계정 중 `APPROVED` 보호소의 `ACTIVE` 담당자만 사용할 수 있어. `MANAGER`와 `STAFF` 모두 가능하고, 일반 사용자나 소속 없는 운영자에게는 권한이 없어.

모든 요청에 `Authorization: Bearer <access_token>`을 보내줘. 본문은 `Content-Type: application/json`을 사용해. 실제 계정 연결·권한 부여, Supabase 원격 DB 서버 실행, 배포와 RN 연결은 아직 별도 작업이야.

## 사용할 API

| 메서드·주소 | 용도 | 성공 응답 |
| --- | --- | --- |
| `GET /v1/shelter-admin/shelters/{shelterId}/dogs` | 관리할 강아지 목록 | 200, `data` 배열·`nextCursor` |
| `GET /v1/shelter-admin/dogs/{dogId}` | 수정할 강아지 정보 | 200, `data` 객체 |
| `POST /v1/shelter-admin/dogs` | 강아지 등록 | 201, 등록된 `data`·`Location` 헤더 |
| `PATCH /v1/shelter-admin/dogs/{dogId}` | 기본 정보·입양 상태·공개 여부 수정 | 200, 수정된 `data` |
| `GET /v1/shelter-admin/dogs/{dogId}/observations` | 관찰 목록 | 200, `data` 배열·`nextCursor` |
| `POST /v1/shelter-admin/dogs/{dogId}/observations` | 관찰 작성 | 201, 작성된 `data` |
| `PATCH /v1/shelter-admin/dogs/{dogId}/observations/{observationId}` | 초안 수정·확인·철회 | 200, 수정된 `data` |

보호소가 비공개여도 승인된 활성 담당자는 관리할 수 있어. 관리 목록에는 비공개·입양 중지·입양 완료 강아지도 나오고, `archived_at`이 있는 보관된 강아지는 제외해. 보관된 강아지는 ID로 조회할 수 있지만 수정이나 관찰 작성·변경은 막아뒀어. 보관·복구·삭제·보호소 이동 API는 이번 범위에 없어.

두 목록은 UUID 오름차순, `limit` 기본 20·최대 50이야. 다음 요청에 받은 `nextCursor`를 `cursor`로 보내면 돼. 보호소·강아지나 목록 종류가 바뀌면 커서를 비워줘. 관찰 목록은 DRAFT·CONFIRMED·RETRACTED를 모두 포함해. 목록은 고정 스냅샷이 아니므로 새 등록은 첫 페이지부터 다시 확인해.

## 강아지 등록

최소 본문은 아래 세 항목이야. `shelterId`는 내 소속 목록에서 고르고, 서버에서도 소속을 다시 확인해.

```json
{
  "shelterId": "02100000-0000-4000-8000-000000000001",
  "name": "새봄",
  "avatarKey": "bomi"
}
```

위 ID는 가상 샘플이야. 샘플 담당자에는 실제 로그인 계정이 연결돼 있지 않아. 새 계정으로 로그인했다고 샘플 보호소를 관리할 수 있는 건 아니야.

생략하면 성별·생일은 UNKNOWN, 입양 상태는 PAUSED, 공개 여부는 false, 태그는 빈 배열이 돼. 생일·품종·체중·중성화 여부·소개는 모르는 상태를 null로 보관해. 확인한 값이 있으면 등록할 때 함께 보내도 돼.

| 항목 | 입력 기준 |
| --- | --- |
| `name` | 필수, 앞뒤 공백 정리 후 1~80자 |
| `avatarKey` | 필수, 1~100자. 영문·숫자로 시작하고 영문·숫자·`/`·`_`·`-`만 허용. 실제 에셋 매핑은 프론트와 확인 |
| `sex` | MALE / FEMALE / UNKNOWN |
| `breed` | null 또는 1~120자 |
| `birthDate`, `birthDatePrecision`, `birthDateEstimated` | 아래 생일 규칙을 함께 적용 |
| `weightKg` | null 또는 0보다 크고 10000보다 작은 숫자. 소수 둘째 자리까지 |
| `neutered` | true 완료 / false 미실시 / null 미확인 |
| `adoptionStatus` | AVAILABLE / IN_PROGRESS / ADOPTED / PAUSED |
| `isPublic` | true / false |
| `traitLabels` | 중복 없는 문자열 배열, 최대 8개·각 1~40자. 빈 배열로 지울 수 있음 |
| `introduction` | null 또는 1~2000자 |

문자열 앞뒤 공백은 정리하고, 빈 문자열이나 JSON 타입이 다른 값은 400으로 알려줘. 생일이 UNKNOWN이면 날짜와 추정 여부가 모두 null이어야 해. YEAR는 해당 연도 1월 1일, MONTH는 해당 월 1일로 저장하고, 알려진 생일에는 추정 여부를 반드시 보내줘. 미래 생일은 한국 날짜 기준으로 거절해. 이 기준 날짜를 실제 생일처럼 표시하면 안 돼.

태그는 보호소 담당자가 확인된 관찰과 대조해 작성하는 요약이야. 서버는 개수·길이·형식을 검사하며 문장의 의미가 관찰과 일치하는지 AI로 판정하지 않아. 품종 일반론을 특정 아이의 성격으로 입력하지 않도록 확인해줘.

## 수정과 덮어쓰기 방지

수정할 항목과 **마지막으로 받은 `updatedAt`을 `expectedUpdatedAt`으로** 보내줘. 생략한 항목은 그대로 두고, null이 가능한 항목에 명시적으로 null을 보내면 비워져. 생일을 미확인으로 바꿀 때도 세 항목을 함께 맞춰야 해.

```json
{
  "expectedUpdatedAt": "2026-09-21T01:20:30.123456Z",
  "adoptionStatus": "AVAILABLE",
  "isPublic": true,
  "weightKg": 8.25,
  "neutered": null
}
```

예시 시각을 그대로 쓰지 말고 응답의 문자열을 그대로 돌려줘. `expectedUpdatedAt`만 보낸 요청은 받지 않아. 다른 담당자가 먼저 수정했다면 **409 STALE_RESOURCE**가 와. 다시 조회해서 변경 내용을 비교한 뒤 제출해줘. 최신 시각만 바꿔서 자동 재전송하면 다른 담당자의 의도를 덮어쓸 수 있어.

`id`, `shelterId`, `species`, `archivedAt`, 작성자, 생성·수정 시각 같은 서버 항목은 PATCH로 바꿀 수 없어. 알 수 없는 항목도 무시하지 않고 400으로 알려줘.

강아지 응답 `data`는 `id`, `shelterId`, `species: DOG`, 위 기본 정보와 `archivedAt`, `createdAt`, `updatedAt`을 포함해. 사진 URL·관찰 기록은 포함하지 않아. 공개 조회에는 승인·공개 보호소의 공개 강아지 중 AVAILABLE·IN_PROGRESS만 나와. ADOPTED·PAUSED로 바꾸거나 공개를 끄면 공개 목록·상세·마릿수에 바로 반영돼. 보호소 자체의 승인·공개 상태는 이 API로 바꿀 수 없어.

## 관찰 기록

```json
{
  "category": "PLAY",
  "content": "공을 두 번 따라간 뒤 그늘에서 쉬었어요.",
  "observedAt": "2026-09-20T09:30:00+09:00",
  "sourceNote": "운동장 오전 관찰"
}
```

`category`, `content`, `observedAt`은 필수야. 관찰 시각은 실제 과거 시각이며 시간대가 있는 ISO 8601 형식을 사용해. 내용은 1~4000자, 선택 항목 `sourceNote`는 null 또는 1~1000자야. 분류는 TEMPERAMENT / ROUTINE / PEOPLE / DOGS / PLAY / CARE / HEALTH / OTHER 중 하나야.

기본 상태는 DRAFT야. 담당자가 확인한 내용을 작성할 때는 `status: CONFIRMED`를 함께 보낼 수 있어. 작성자·확인자와 확인 시각은 서버가 로그인 정보로 채우므로 본문에 넣지 않아. 새 기록을 RETRACTED로 만들 수는 없어.

| 현재 상태 | 가능한 변경 |
| --- | --- |
| DRAFT | 내용·분류·관찰 시각·출처 수정, CONFIRMED로 확인, RETRACTED로 철회 |
| CONFIRMED | `status: RETRACTED`로 철회만 가능. 내용 정정은 새 기록 작성 |
| RETRACTED | 변경 불가 |

관찰 PATCH에도 해당 **관찰 기록의** `expectedUpdatedAt`이 필요해. 강아지의 시각을 보내면 안 돼. 확인 시에는 확인한 담당자와 시각을 기록하고, 철회할 때도 원래 내용·작성자·확인 이력을 유지해. 확인 내용을 덮어쓰거나 철회 기록을 되살리려 하면 409 OBSERVATION_LOCKED야. 별도의 전체 변경 이력이나 철회자 필드는 현재 스키마에 없어.

응답은 `id`, `dogId`, `category`, `content`, `observedAt`, `recordedBy`, `sourceNote`, `status`, `confirmedBy`, `confirmedAt`, `createdAt`, `updatedAt`이야. 작성자·확인자는 서비스 사용자 ID이고 Supabase Auth ID와 달라. 다른 강아지의 관찰 ID를 넣어 수정할 수 없어. B-07의 AI 근거에는 해당 강아지의 CONFIRMED 기록만 사용해야 해.

## 실패했을 때

오류는 `code`, `message`, `requestId`야. 성공·오류 모두 `X-Request-ID`, `Cache-Control: no-store`를 사용해.

| HTTP / code | 의미 |
| --- | --- |
| 400 INVALID_REQUEST / INVALID_CURSOR | 누락·잘못된 입력·수정 불가 항목·잘못된 커서 |
| 401 UNAUTHENTICATED | 토큰 누락·위조·만료 |
| 403 ACCOUNT_NOT_REGISTERED / ACCOUNT_DISABLED / FORBIDDEN | 사용자 등록 전·중지 계정·관리 권한 없음 |
| 404 RESOURCE_NOT_FOUND | 접근 가능한 강아지 아래 요청한 관찰이 없음 등 |
| 409 STALE_RESOURCE | 조회한 뒤 다른 수정이 저장됨 |
| 409 OBSERVATION_LOCKED / DOG_ARCHIVED | 상태상 수정할 수 없음 |
| 409 WRITE_CONFLICT | 동시에 처리 중인 작업과 충돌. 재조회 후 시도 |
| 415 UNSUPPORTED_MEDIA_TYPE | JSON이 아닌 요청 본문 |
| 500 INTERNAL_ERROR | 서버 오류. 내부 DB 내용은 노출하지 않음 |

없는 보호소·강아지와 다른 보호소의 데이터는 권한 검사에서 403으로 구분 없이 막아. POST는 요청마다 새 기록을 생성하므로 응답을 못 받았다고 자동으로 반복 호출하지 말고 관리 목록을 먼저 확인해. 별도 요청 중복 방지 키는 이번 범위에 없어.

## 서버 검증과 적용 범위

변경 트랜잭션 안에서 계정·소속·승인 행에 공유 잠금을, 수정할 강아지와 관찰에는 수정 잠금을 잡아. 잠금을 기다리는 동안 강아지 소속이 바뀌면 다시 확인해 차단해. [PostgreSQL 행 잠금 규칙](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)을 사용하며, 이전 `/access` 성공 응답을 저장 권한으로 인정하지 않아.

기존 V1의 `updated_at`과 트리거를 사용해서 새 마이그레이션은 없어. DB 구조·RLS·실제 Supabase 데이터·담당자 권한은 변경하지 않았어. 입력·HTTP 및 PostgreSQL 검사에서 공개 조건, 관찰 상태, 타 보호소 차단, 동시 수정, 권한 변경을 기다린 뒤 재검사하는 동작을 확인해.

다음 B-10에서 8개 동작의 행동 설정을 구현해. 사진 업로드·조회, 대화, 입양 준비 메모는 각각의 후속 작업이야.
