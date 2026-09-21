# 입양 준비 메모 · B-09

강아지에게 더 물어볼 질문과 함께 살 때의 돌봄 계획을 저장하는 개인 메모야. 사용자 한 명이 강아지 한 마리당 메모 하나를 갖고, 다시 들어와 이어서 수정할 수 있어. 입양 신청서나 보호소에 제출하는 서류는 아니야.

Supabase 로그인 후 `POST /v1/me`로 서비스 사용자를 등록하고, 요청마다 `Authorization: Bearer <access_token>`을 보내줘. 사용자 ID를 본문에 넣지 않아. 보호소 담당자와 운영자도 다른 사람의 메모를 읽거나 수정할 수 없어.

## 사용할 주소

| 메서드 | 주소 | 하는 일 |
| --- | --- | --- |
| GET | `/v1/me/adoption-notes` | 내 메모 목록 |
| GET | `/v1/me/adoption-notes/{dogId}` | 해당 강아지에 대해 내가 쓴 메모 |
| PUT | `/v1/me/adoption-notes/{dogId}` | 처음 저장하거나 전체 내용 교체 |

주소의 ID는 **강아지 ID**야. 응답의 메모 `id`를 넣는 주소가 아니야. 삭제·부분 수정·신청 제출 API는 제공하지 않아. 내용을 지우려면 최신 수정 시각과 함께 빈 문자열·빈 체크 객체를 저장하면 돼.

메모가 없으면 단건 조회는 `404 NOTE_NOT_FOUND`, 목록은 `200`과 빈 `data`를 반환해. 조회만으로 빈 메모를 만들지는 않아.

## 처음 저장하기

봄이에게 쓸 메모의 예시야.

```http
PUT /v1/me/adoption-notes/02200000-0000-4000-8000-000000000001
Authorization: Bearer <access_token>
Content-Type: application/json
```

```json
{
  "questions": "혼자 있는 시간은 어느 정도가 편할까?\n좋아하는 산책 코스도 물어보기",
  "carePlan": "아침에는 내가, 저녁에는 가족이 산책하기",
  "checklist": {
    "householdDiscussed": true,
    "housingChecked": true,
    "careTimePlanned": false,
    "budgetPlanned": false,
    "shelterQuestionsPrepared": true
  },
  "expectedUpdatedAt": null
}
```

처음 저장하면 `201 Created`, 이후 수정하면 `200 OK`야. 두 응답 모두 `Location` 헤더에 같은 강아지의 메모 조회 주소가 들어 있어. 응답 예시의 ID와 시각은 설명용이야.

```json
{
  "data": {
    "id": "09000000-0000-4000-8000-000000000001",
    "dogId": "02200000-0000-4000-8000-000000000001",
    "questions": "혼자 있는 시간은 어느 정도가 편할까?\n좋아하는 산책 코스도 물어보기",
    "carePlan": "아침에는 내가, 저녁에는 가족이 산책하기",
    "checklist": {
      "householdDiscussed": true,
      "housingChecked": true,
      "careTimePlanned": false,
      "budgetPlanned": false,
      "shelterQuestionsPrepared": true
    },
    "createdAt": "2026-09-21T03:00:00.123456Z",
    "updatedAt": "2026-09-21T03:00:00.123456Z"
  }
}
```

메모 본문에는 사용자 ID, 강아지 사진, 보호소의 비공개 정보가 포함되지 않아. 개인 메모를 AI 답변의 근거로 사용하거나 보호소에 전송하지도 않아. 텍스트는 일반 문자열이므로 앱에서 HTML로 실행하지 말고 텍스트로 표시해줘.

## 입력 기준

`questions`, `carePlan`, `checklist`, `expectedUpdatedAt` 네 필드는 모두 필요해. 추가 필드나 잘못된 형식은 `400 INVALID_REQUEST`야.

| 필드 | 입력 |
| --- | --- |
| `questions` | 문자열, 최대 5,000자 |
| `carePlan` | 문자열, 최대 10,000자 |
| `checklist` | 아래 키와 boolean 값으로 이루어진 객체 |
| `expectedUpdatedAt` | 처음 저장은 `null`, 수정은 직전 응답의 `updatedAt` |

질문과 계획은 빈 문자열을 허용하고 줄바꿈·앞뒤 공백도 그대로 보존해. 글자 수는 유니코드 코드 포인트 기준이고 NUL 문자는 허용하지 않아. `null`로 내용을 지우지는 못해.

| 체크 키 | 화면에서 사용할 의미 |
| --- | --- |
| `householdDiscussed` | 함께 사는 사람들과 이야기했어요 |
| `housingChecked` | 반려동물과 살 수 있는 주거 환경을 확인했어요 |
| `careTimePlanned` | 산책과 돌봄 시간을 생각해봤어요 |
| `budgetPlanned` | 생활비와 진료비를 생각해봤어요 |
| `shelterQuestionsPrepared` | 보호소에 물어볼 질문을 준비했어요 |

각 항목은 개인 준비 상태야. 보호소가 요구하는 서류나 입양 자격을 확인했다는 뜻은 아니야. `{}`도 허용하고 일부 키만 보내도 돼. 생략한 키는 미기록으로 두며, 명시적인 `false`와 구분해서 저장해. PUT은 전체 교체이므로 기존 체크를 유지하려면 유지할 키도 함께 보내줘.

## 수정과 충돌 처리

1. 단건 GET으로 최신 메모를 받아.
2. 응답의 `updatedAt` **원문 문자열을 그대로** `expectedUpdatedAt`에 넣어. JavaScript `Date`로 바꾸면 마이크로초가 잘릴 수 있어.
3. 수정한 질문·계획·체크를 모두 PUT으로 보내.
4. 성공하면 새 응답의 `updatedAt`을 다음 수정에 사용해.

이미 메모가 있는데 `null`로 다시 만들거나 오래된 시각을 보내면 `409 NOTE_VERSION_CONFLICT`야. 다른 기기에서 수정한 내용을 덮어쓰지 않도록, 내가 작성 중인 내용은 화면에 남겨두고 서버의 최신 메모를 다시 불러와 비교해줘. 최신 시각을 자동으로 끼워 넣어 무조건 재전송하지 않아야 해.

같은 시각·같은 내용이면 수정 시각을 바꾸지 않고 기존 메모를 반환해. 네트워크 오류로 저장 결과를 받지 못하면 GET으로 저장 여부부터 확인해. 첫 요청의 재전송도 이미 저장된 상태라면 `409`가 올 수 있어. 메모가 없는데 수정 시각을 보내면 `404 NOTE_NOT_FOUND`야.

동시 생성은 한 요청만 성공하고 다른 요청은 `409`가 돼. 같은 버전에서 서로 다른 내용으로 동시에 수정해도 한 요청만 성공해. 사용자와 메모의 잠금을 잡고 저장 직전에 계정·버전을 확인해.

## 강아지 공개 상태가 바뀌면

처음 작성할 때는 승인·공개 보호소의 공개 강아지 중 `AVAILABLE` 또는 `IN_PROGRESS`만 허용해. 없는 강아지, 비공개, 보호 중단, 입양 완료, 보관 처리된 강아지는 동일한 `404 DOG_NOT_FOUND`야.

이미 작성한 메모는 강아지가 입양 완료되거나 비공개로 바뀌어도 본인이 조회하고 수정할 수 있어. 과거에 작성한 개인 기록을 잃지 않게 하기 위한 동작이야. 이때 강아지의 새 비공개 프로필이나 사진을 함께 내려주지는 않아. 비활성화된 계정은 메모에도 접근할 수 없어.

## 목록과 오류

`GET /v1/me/adoption-notes?limit=20&cursor=...`로 조회해. `limit` 기본값은 20, 허용 범위는 1~50이야. 응답은 `{ "data": [...], "nextCursor": "..." }` 형식이고 마지막 페이지의 커서는 `null`이야. 메모 생성 시각과 ID를 내림차순으로 정렬하므로 수정해도 목록 순서가 바뀌지 않아. 새 메모는 첫 페이지를 다시 조회해서 반영해줘.

커서는 현재 사용자의 목록에만 사용할 수 있어. 다른 사용자·다른 종류의 목록 커서와 섞으면 `400 INVALID_CURSOR`야. 커서는 인증 수단이 아니며, 조회는 항상 로그인된 사용자로 제한해.

| 상태 | 코드 | 처리 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_CURSOR` | 필드·강아지 ID·수정 시각·목록 조건 확인 |
| 401 | `UNAUTHENTICATED` | 로그인 토큰 확인 |
| 403 | `ACCOUNT_NOT_REGISTERED`, `ACCOUNT_DISABLED` | 서비스 사용자 등록 또는 계정 상태 확인 |
| 404 | `NOTE_NOT_FOUND` | 내가 저장한 메모가 없음 |
| 404 | `DOG_NOT_FOUND` | 새 메모를 만들 수 없는 강아지 |
| 409 | `NOTE_VERSION_CONFLICT`, `WRITE_CONFLICT` | 입력을 보존하고 최신 상태 재조회 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JSON으로 전송 |
| 500 | `INTERNAL_ERROR` | 저장 여부를 다시 확인하고 필요하면 재시도 |

오류는 `{ "code": "...", "message": "...", "requestId": "..." }` 형식이야. 모든 응답은 `Cache-Control: no-store`이고, `requestId`는 `X-Request-ID` 헤더와 같아.

## 적용 범위

기존 V1의 `adoption_notes`와 사용자·강아지 조합 UNIQUE, 수정 시각 트리거를 사용해. 새 마이그레이션이나 환경변수는 없어. 실제 Supabase의 구조·데이터·권한·RLS를 변경하지 않았고, 서버 배포와 RN 화면 연결은 별도야. 지정 개발 DB는 B-10A에서 V3까지 적용한 상태라 메모를 위한 추가 SQL은 필요하지 않아.

자동 검사에서는 실제 JWT 서명, 본인 소유권, 빈 메모·체크 교체, 버전 충돌, 목록 이동, 동시 생성·수정, 잠금 대기 중 계정·공개 상태 변경을 확인해. 실제 Supabase 로그인·원격 JDBC·앱 전체 연결을 완료했다는 뜻은 아니야.
