# 강아지 기록 기반 답변 · B-07

B-06으로 사용자 메시지를 저장한 다음, 그 메시지에 답변 생성을 요청하면 돼. 서버가 해당 강아지의 확인된 관찰 기록을 읽고 OpenAI Responses API로 답변을 요청해. 말투는 강아지의 1인칭이고, 실제 강아지가 말하는 것이 아니라 기록을 소개하는 AI 캐릭터라는 점은 화면에서 알려줘.

기본 모델은 `gpt-5.6-luna`야. [공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna)에서 Responses와 Structured Outputs 지원을 확인했어. Q-02에서 실제 키 호출과 가상 기록 질문 11개의 표시 답변·근거·저장·재시도를 확인했어. [실제 답변과 사용량, 검증 범위](live-ai-verification.md)를 참고해. 모의 HTTP·PostgreSQL 검사도 계속 유지해.

## 프론트 연결 순서

1. B-06 `POST /v1/chat-sessions/{sessionId}/messages`에 `clientMessageId`, `text`를 보내서 저장해.
2. 응답의 사용자 메시지 `id`로 아래 `/reply`를 호출해. 기존 메시지 전송 API의 응답은 바뀌지 않았어.
3. 완료된 `reply`를 화면에 추가하거나 B-06 메시지 목록을 다시 조회해. 메시지 `id`로 합치면 같은 답변이 중복 표시되지 않아.
4. 실패한 경우 `retryable`을 확인하고 사용자가 재시도할 때만 `{ "retry": true }`로 같은 `/reply`를 호출해.

```http
POST /v1/chat-sessions/{sessionId}/messages/{messageId}/reply
Authorization: Bearer <access_token>
```

첫 요청은 본문 없음 또는 `{}`. 재시도는 `Content-Type: application/json`과 `{ "retry": true }`를 사용해. 질문·강아지·모델·프롬프트·근거 ID를 이 요청에 넣을 수는 없어. 서버가 저장된 사용자 메시지와 본인 대화방에서 찾아.

생성은 이 HTTP 요청 안에서 처리하고, 외부 호출은 기본 30초까지만 기다려. DB 연결이나 행 잠금을 잡은 채 AI 응답을 기다리지 않아. 별도 큐·자동 처리 작업·SSE는 이번에 추가하지 않았어. 메시지를 저장하기만 하고 `/reply`를 호출하지 않으면 PENDING으로 남아.

## 응답

답변을 새로 저장하면 201, 이미 있는 답변은 200이야. 대화방의 다른 사용자 메시지로 바뀌거나 새 답변이 추가되지는 않아.

```json
{
  "data": {
    "requestMessageId": "03100000-0000-4000-8000-000000000001",
    "processingStatus": "COMPLETED",
    "failureCode": null,
    "retryable": false,
    "reply": {
      "id": "03100000-0000-4000-8000-000000000002",
      "sessionId": "03000000-0000-4000-8000-000000000001",
      "dogId": "02200000-0000-4000-8000-000000000001",
      "role": "ASSISTANT",
      "text": "난 공을 천천히 따라가는 걸 좋아해!",
      "clientMessageId": null,
      "replyToMessageId": "03100000-0000-4000-8000-000000000001",
      "processingStatus": "COMPLETED",
      "failureCode": null,
      "needsShelterConfirmation": false,
      "createdAt": "2026-09-21T00:00:02.123456Z",
      "updatedAt": "2026-09-21T00:00:02.123456Z"
    }
  }
}
```

ID·시각·내용은 형식을 설명하는 가상 예시야. 원래 USER 메시지도 COMPLETED가 되고, ASSISTANT 답변과 사용한 근거 스냅샷을 같은 트랜잭션에 저장해. 도중에 실패하면 답변·근거·완료 상태를 함께 롤백해.

| HTTP / 상태 | 의미와 화면 처리 |
| --- | --- |
| 201 / COMPLETED | 새 답변 저장. `reply` 표시 |
| 200 / COMPLETED | 기존 답변 재사용. 같은 ID의 답변은 추가로 표시하지 않기 |
| 202 / PENDING | 같은 메시지의 답변 생성이 진행 중. 자동 생성 재시도 없이 나중에 조회 |
| 200 / FAILED | 실패 상태. `reply`는 null이며, `retryable`이 true일 때 명시적으로 재시도 가능 |
| 503 / AI_NOT_CONFIGURED | 서버의 AI 기능이 꺼져 있음. 새 시도를 선점하거나 API를 호출하지 않음 |

FAILED도 상태 조회 결과이므로 HTTP 200일 수 있어. HTTP 코드만 보고 답변이 성공했다고 표시하면 안 돼.

```json
{
  "data": {
    "requestMessageId": "03100000-0000-4000-8000-000000000001",
    "processingStatus": "FAILED",
    "failureCode": "AI_TIMEOUT",
    "retryable": true,
    "reply": null
  }
}
```

다른 사용자의 대화방은 404, 해당 방의 사용자 메시지가 아니면 `404 MESSAGE_NOT_FOUND`야. 미등록·정지 계정은 B-04와 같은 403을 반환해. 새 생성 시 종료된 방은 `409 SESSION_CLOSED`, 비공개·입양 완료·중지·보관된 강아지는 `409 DOG_UNAVAILABLE`이야. 이미 저장한 답변은 본인이 계속 조회할 수 있어.

한 방에서는 한 번에 한 메시지의 답변만 생성해. 다른 메시지의 생성 요청은 `409 REPLY_IN_PROGRESS`이므로 앞선 답변이 끝난 후 요청해. 근거 기록이 없는 질문과 짧은 인사는 정해둔 문구로 처리해서 모델을 호출하지 않아. AI 기능을 켰을 때만 이 경로가 동작해.

## 근거와 말투

서버가 전달하는 정보는 강아지 이름, 현재 질문, 같은 방의 이전 사용자 발언 최대 6개·6,000자, 같은 강아지의 CONFIRMED 관찰 최대 16개·16,000자야. 최근 관찰 32개 중 예산에 들어가는 기록을 고르고 기록을 중간에 잘라 전달하지 않아. 빠진 기록의 사실을 추정해서 답하면 안 돼.

DRAFT·RETRACTED·다른 강아지 기록, 사용자 식별 정보, 작성자·확인자·내부 sourceNote·사진은 전달하지 않아. 이전 AI 답변은 근거로 다시 사용하지 않아. 과거 사용자 발언이나 관찰 본문의 지시문도 명령으로 취급하지 않도록 분리해.

[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)로 `text`, `needsShelterConfirmation`, `observationIds`를 받고 서버에서 다시 검사해. 모델은 웹이나 DB를 직접 조회할 도구가 없고, 제공하지 않은 근거 ID나 빈 근거로 사실을 주장한 응답은 표시하지 않아. `needsShelterConfirmation=true`인 모델 답변도 그대로 노출하지 않고 아래 안내로 바꿔.

> 아직 그 부분은 내 기록에 없어서 확실히 말하기 어려워. 보호소에 함께 확인해 줄래?

완성된 응답을 저장하기 직전에 계정·대화방·강아지 공개 조건과 사용한 관찰의 상태·내용·수정 시각을 다시 검사해. 생성 도중 인용한 기록이 철회·변경되면 보호소 확인 안내로 대체하고 그 기록을 근거로 저장하지 않아. 저장 후 과거 관찰이 변경돼도 당시의 스냅샷은 유지해.

**근거 ID가 유효하다는 검사만으로 문장의 모든 주장이 정확하다는 것을 증명하지는 못해.** 건강·입질·합사 안전·입양 승인 등은 단정하지 않도록 요청하지만, Q-02에서는 실제 질문 11개의 표시 답변을 기록과 대조했어. 이 작은 표본이 모든 질문의 정확성을 보장하지는 않으므로 기록·모델·지시문이 달라지면 다시 검증해.

## 실패와 재시도

내부 오류 본문이나 API 키를 클라이언트에 보내지 않고 고정된 코드로 저장해.

- `AI_TIMEOUT`, `AI_UNAVAILABLE`, `AI_RATE_LIMITED`: 시간 초과·네트워크/서버 오류·요청 제한.
- `AI_AUTH_FAILED`: API 키나 사용 권한 확인 필요.
- `AI_INCOMPLETE`, `AI_INVALID_RESPONSE`: 미완성·잘못된 형식. 답변은 저장하지 않음.
- `GENERATION_EXPIRED`: 서버 중단 또는 생성 작업 유효 시간 만료.
- `CONTEXT_UNAVAILABLE`, `ACCOUNT_UNAVAILABLE`: 생성 중 공개 조건이나 계정 상태 변경.
- `AI_INTERNAL_ERROR`: 처리·저장 오류. 답변과 근거 저장은 함께 취소됨.

재전송만으로 FAILED를 초기화하지 않아. `{ "retry": true }`가 있어야 새 시도를 시작하고, 메시지당 최초 시도를 포함해 최대 3번까지야. `retryable`은 남은 시도 횟수 기준이고, 실제 재시도에는 로그인·공개 상태·설정 검사를 다시 적용해.

작업 선점 토큰은 DB에 저장하고 기본 90초(호출 제한 + 60초) 동안 유효해. 같은 토큰이 유효한 동안 중복 요청은 202를 받고 모델을 다시 호출하지 않아. 서버가 중단되면 다음 `/reply` 요청에서 만료된 작업을 FAILED로 바꾸며, 명시적 재시도 때 새 토큰을 발급해. 자동 복구 작업은 없어. 오래된 작업은 나중에 끝나도 새 시도의 답변을 덮어쓸 수 없어.

외부 모델 호출과 DB 커밋은 하나의 트랜잭션이 아니야. 응답 유실·시간 초과·서버 중단 후 재시도하면 이전 호출에도 요금이 생겼을 수 있어. 이 구현은 DB 답변 중복과 동시 실행을 제어하지만 외부 과금의 정확히 한 번 실행을 보장하지는 않아. OpenAI 요청을 자동으로 재시도하지 않아.

## 서버 설정과 DB 적용

기본은 꺼져 있어. 서버 환경변수로만 설정하고 API 키를 RN 앱·웹 코드·GitHub·노션에 넣지 마.

```dotenv
AI_ENABLED=true
OPENAI_API_KEY=<서버 환경에 설정>
OPENAI_MODEL=gpt-5.6-luna
AI_TIMEOUT_SECONDS=30
```

`AI_ENABLED=true`인데 키가 없으면 서버 시작을 중단해. 호출 대상은 `https://api.openai.com/v1/responses`로 고정하고 리다이렉트를 따라가지 않아. `store=false`, `reasoning.effort=low`, 출력 최대 2,000토큰을 사용해. 변경 모델도 이 요청 형식을 지원해야 해. `store=false`를 모든 제공자 보관 정책이 해제된다는 뜻으로 해석하면 안 돼. [공식 데이터 정책](https://developers.openai.com/api/docs/guides/your-data)을 참고해.

V2 마이그레이션은 `chat_messages`에 선점 토큰·만료 시각·시도 횟수·모델·응답 ID와 인덱스를 추가해. V1 파일과 기존 RLS·클라이언트 권한은 바꾸지 않았어. 서버에는 V2 이상 DB가 필요해. 지정 개발 프로젝트는 B-10A에서 V3까지 적용했어.

**2026.09.21 지정 Supabase 개발 프로젝트에 V2·V3를 적용했어.** [실제 확인 결과](supabase-development-db.md)를 참고해. 새 프로젝트용 초기화 SQL은 여전히 V1만 만들므로 후속 버전을 별도로 적용해야 해. 기존 DB에 초기화 SQL을 다시 실행하면 안 돼.

로컬 빌드·모의 OpenAI HTTP 검사, CI의 V1→V2 이행·PostgreSQL 저장·동시 생성·오류/재시도 검사를 사용해. 실제 키 호출·가상 질문 답변 검토는 Q-02에서, 원격 JDBC 연결은 B-11에서 확인했어. RN 화면 연결과 외부 서버 배포는 남아 있어.
