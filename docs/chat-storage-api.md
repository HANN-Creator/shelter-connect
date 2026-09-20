# 대화방·메시지 저장 API · B-06

같은 강아지와 다시 이야기할 때 기존 대화방을 이어서 쓰고, 보낸 메시지를 다시 불러올 수 있어. Supabase access token을 `Authorization: Bearer <token>`으로 보내고, B-04의 `POST /v1/me`로 서비스 사용자 등록을 먼저 해줘.

이 저장 API는 사용자 메시지를 PENDING으로 저장해. 다음으로 [B-07 답변 생성 API](grounded-chat-api.md)를 호출하면 답변을 생성하고 저장할 수 있어. 저장만으로 AI가 자동 실행되는 것은 아니야.

## 요청 경로

| 요청 | 결과 |
| --- | --- |
| `POST /v1/dogs/{dogId}/chat-sessions` | 열린 대화방 재사용 200 / 없으면 생성 201. 본문 없이 호출하거나 `{}` 전달 |
| `GET /v1/me/chat-sessions` | 내 대화방 목록. 선택적으로 `dogId` 필터 |
| `GET /v1/chat-sessions/{sessionId}` | 내 대화방 상세와 현재 `canSend` |
| `GET /v1/chat-sessions/{sessionId}/messages` | 내 대화방의 메시지 목록 |
| `POST /v1/chat-sessions/{sessionId}/messages` | 사용자 메시지 저장 201 / 같은 요청 재전송 200 |

대화방 목록은 **생성 시각 내림차순**, 메시지는 **생성 시각 오름차순**이야. 같은 시각이면 UUID로 순서를 고정해. 기본 20개, 최대 50개이며 `limit`으로 조절할 수 있어. 응답의 `nextCursor`를 다음 요청의 `cursor`로 보내고, null이면 현재 마지막 페이지야. 사용자·강아지 필터·대화방·목록 종류가 바뀌면 커서를 버려줘.

대화방 목록은 최근 메시지 순서가 아니야. 메시지를 보내도 페이지 사이에서 대화방이 이동하지 않도록 생성 순서를 사용해. `updatedAt`은 새 메시지를 저장할 때 갱신해. 메시지의 마지막 페이지에서 갱신된 답변 상태를 확인하려면 다시 조회해야 해. 실시간 구독·폴링 전용 변경 커서·스트리밍은 이번에 추가하지 않았어.

## 대화방 만들기와 이어 쓰기

```http
POST /v1/dogs/02200000-0000-4000-8000-000000000001/chat-sessions
Authorization: Bearer <access_token>
```

```json
{
  "data": {
    "id": "03000000-0000-4000-8000-000000000001",
    "dogId": "02200000-0000-4000-8000-000000000001",
    "status": "OPEN",
    "canSend": true,
    "createdAt": "2026-09-21T00:00:00.123456Z",
    "updatedAt": "2026-09-21T00:00:00.123456Z"
  }
}
```

ID와 시각은 형식을 보여주는 가상 예시야. 생성·재사용 응답의 `Location`에는 대화방 상세 경로가 들어 있어.

같은 사용자·강아지의 OPEN 대화방 중 가장 최근 생성된 방을 재사용해. 처음 두 요청이 동시에 들어와도 이 API에서는 방 하나만 만들어. 기존 구조상 여러 OPEN 행이 있을 수 있지만 임의로 합치거나 지우지는 않아. CLOSED 방만 있으면 새 방을 만들고 이전 방의 기록은 유지해. 대화방 종료·삭제·사용자나 강아지 변경 API는 없어.

## 메시지 보내기

```http
POST /v1/chat-sessions/03000000-0000-4000-8000-000000000001/messages
Content-Type: application/json
Authorization: Bearer <access_token>
```

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "text": "무슨 놀이를 좋아해?"
}
```

```json
{
  "data": {
    "id": "03100000-0000-4000-8000-000000000001",
    "sessionId": "03000000-0000-4000-8000-000000000001",
    "dogId": "02200000-0000-4000-8000-000000000001",
    "role": "USER",
    "text": "무슨 놀이를 좋아해?",
    "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
    "replyToMessageId": null,
    "processingStatus": "PENDING",
    "failureCode": null,
    "needsShelterConfirmation": false,
    "createdAt": "2026-09-21T00:00:01.123456Z",
    "updatedAt": "2026-09-21T00:00:01.123456Z"
  }
}
```

- `clientMessageId`: 앱에서 메시지당 한 번 생성해. 1~128자, 영문·숫자로 시작하고 나머지는 영문·숫자·`.`·`_`·`:`·`-`만 허용해. UUID 문자열을 쓰면 돼.
- `text`: 문자열 1~4,000자. 공백만 있는 내용과 NUL 문자는 거절해. 이모지는 Unicode 코드 포인트 단위로 세고, 본문의 공백·줄바꿈은 그대로 보관해.
- 두 필드만 허용해. `userId`, `dogId`, `role`, 처리 상태, 작성 시각이나 답변 ID를 클라이언트가 정할 수 없어.
- 재전송할 때는 같은 대화방에 **같은 ID와 정확히 같은 본문**을 보내줘. 응답이 끊겼다고 새 ID를 만들면 새 메시지로 저장돼.
- 같은 ID·같은 본문은 기존 메시지의 현재 상태를 200으로 돌려줘. 같은 ID·다른 본문은 `409 MESSAGE_ID_CONFLICT`. 같은 본문이라도 ID가 다르면 별개의 메시지야.
- ID의 중복 범위는 대화방이야. 다른 대화방에서는 같은 ID를 사용할 수 있어.

메시지 목록은 `{ "data": [...], "nextCursor": null }` 형식이고, 각 항목은 저장 응답과 같아. 비어 있으면 빈 배열이야. 저장된 ASSISTANT 메시지도 함께 조회하며 역할은 `USER` / `ASSISTANT` 대문자를 사용해. 내부 관찰 기록·근거 내용·사진 URL은 응답에 없어.

FAILED나 COMPLETED 사용자 메시지를 재전송해도 PENDING으로 되돌리지 않아. B-07의 `/reply`에서 명시적 재시도와 작업 선점·실패 복구를 처리해. 이번 저장 재전송 처리가 AI 중복 호출이나 중복 과금까지 해결하는 것은 아니야.

## 접근과 상태 변경

일반 사용자·보호소 담당자·운영자 모두 **본인의 대화만** 읽고 쓸 수 있어. 다른 사람의 대화방과 없는 대화방은 같은 404를 반환해. 미등록·정지 계정은 읽기와 쓰기를 모두 거절해.

새 대화방을 열거나 새 메시지를 보낼 수 있는 조건은 공개 조회와 같아. 보호소가 APPROVED·공개 상태이고, 강아지가 공개·보관되지 않음·AVAILABLE 또는 IN_PROGRESS여야 해. `canSend`는 이 조건과 대화방 OPEN 여부를 알려주지만 이후 전송 권한을 보장하는 값은 아니야. 서버가 매번 다시 확인해.

강아지·보호소가 비공개로 바뀌거나 입양 완료·중지·보관 상태가 되면 새 대화와 새 메시지를 막아. 본인의 기존 대화방과 메시지는 계속 읽을 수 있어. 이미 저장한 메시지의 동일 재전송도 기존 결과를 반환하므로 저장 여부를 확인할 수 있어. 이때 새 메시지를 추가하지는 않아.

## 오류

공통 본문은 `code`, `message`, `requestId`이고 모든 응답에 `Cache-Control: no-store`를 사용해.

| HTTP / code | 처리 |
| --- | --- |
| 400 `INVALID_REQUEST` / `INVALID_CURSOR` | ID·본문·타입·길이·페이지 값을 확인. 커서 오류면 첫 페이지 재조회 |
| 401 `UNAUTHENTICATED` | 토큰을 확인하고 로그인 흐름으로 연결 |
| 403 `ACCOUNT_NOT_REGISTERED` / `ACCOUNT_DISABLED` | 서비스 등록 전 / 정지 계정 |
| 404 `CHAT_NOT_FOUND` | 없거나 본인의 대화방이 아님 |
| 404 `DOG_NOT_FOUND` | 대화방 생성 대상이 없거나 현재 대화 불가 |
| 409 `MESSAGE_ID_CONFLICT` | 같은 전송 ID에 다른 내용. 원래 요청을 확인 |
| 409 `SESSION_CLOSED` / `DOG_UNAVAILABLE` | 새 메시지 불가. 기존 대화는 조회 가능 |
| 409 `WRITE_CONFLICT` | 다른 작업과 충돌. 재조회 후 같은 메시지 ID·본문으로 재전송 |
| 415 `UNSUPPORTED_MEDIA_TYPE` | 본문은 application/json 사용 |
| 500 `INTERNAL_ERROR` | 일시적인 서버 오류. requestId를 남기고 같은 요청으로 재시도 |

## 저장과 검증 범위

사용자 계정 행을 잠가 정지 여부와 대화방 생성을 확인하고, 메시지 저장은 대화방 행을 잠근 뒤 중복 요청과 현재 상태를 검사해. 새 데이터 작성 시 강아지·보호소 공개 상태도 공유 잠금으로 유지해. 메시지와 대화방 수정 시각은 같은 트랜잭션에서 저장해. 메시지 생성 시각은 대기 종료 후 정하며, 같은 대화방의 이전 메시지보다 뒤에 오도록 해서 커서 순서를 지켜. [PostgreSQL 행 잠금 문서](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)를 기준으로 구현했어.

B-06 저장 API는 V1 구조를 사용해. 이후 B-07 답변 생성에는 V2와 AI 환경변수가 필요하고, 기존 RLS는 그대로 유지해. 실제 Supabase 데이터·계정 권한·Auth 설정은 바꾸지 않았어. CI에서는 임시 PostgreSQL과 테스트 JWT로 실제 저장·소유권·동시 요청·상태 변경 대기를 확인해. 실제 Supabase 계정+원격 JDBC 전체 연결, 배포, RN 화면 연결은 별도로 남아 있어.
