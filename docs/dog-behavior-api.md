# 강아지 행동 설정 연결하기

미리 만든 8종 동작을 강아지마다 다르게 조합하는 설정이야. 서버는 속도·비중·거리와 관찰 근거를 저장하고, 앱은 상황에 맞는 동작을 선택해서 재생해. 재생 중 AI 호출이나 새 이미지 생성은 없어.

## API

| 메서드·경로 | 용도 | 인증 |
| --- | --- | --- |
| `GET /v1/dogs/{dogId}/behavior` | 앱에서 쓸 설정. 공개 강아지만 조회 | 둘러보기와 같이 로그인 없이 가능 |
| `GET /v1/shelter-admin/dogs/{dogId}/behavior` | 초안·확인 상태·근거·수정 버전 조회 | 같은 보호소의 활성 MANAGER / STAFF |
| `PUT /v1/shelter-admin/dogs/{dogId}/behavior` | 전체 설정을 초안으로 저장·교체 | 위와 같음 |
| `POST /v1/shelter-admin/dogs/{dogId}/behavior/confirmation` | 현재 설정을 보호소가 확인 | 위와 같음 |

관리 API는 Supabase JWT와 서버의 계정·소속·보호소 승인 상태를 검사해. OPERATOR라는 이유만으로 다른 보호소를 관리할 수 없어. 같은 보호소의 비공개 강아지는 관리할 수 있지만 보관된 강아지는 수정·확인할 수 없어. 관리 GET에서 아직 설정이 없으면 `200 {"data":null}`이야.

## 저장 → 확인 → 공개

1. 관리 GET으로 현재 `revision`을 읽어. 없으면 `expectedRevision: 0`으로 시작해.
2. PUT에는 아래 항목을 모두 보내. 누락·추가 필드, 문자열로 보낸 숫자, 범위 밖 수치는 `400 INVALID_BEHAVIOR`야. 저장은 항상 `DRAFT`이며 기존 확인자·확인 시각을 지워.
3. 확인할 설정과 근거를 검토하고 confirmation에 `{"expectedRevision":1}`처럼 **조회한 버전**을 보내. 실제 확인자와 시각은 서버가 넣어.
4. 저장·상태 변경마다 revision이 1씩 늘어. 예를 들어 첫 저장 1 → 확인 2 → 수정 3이야. 오래된 버전은 `409 STALE_RESOURCE`; 최신 설정을 다시 읽고 수정해. 동시에 처음 저장해도 하나만 성공해.
5. 이미 확인된 현재 버전을 다시 확인하면 같은 설정을 반환해. 최초 확인 직후 예전 버전으로 재전송하면 409이므로 조회해서 결과를 확인해.

`source`는 `SHELTER` 또는 `AI_SUGGESTED`. 둘 다 보호소 확인 전에는 공개하지 않아. **AI 초안을 자동으로 만드는 기능은 이번 API에 포함하지 않았어.** 향후 제안 결과도 이 검증·확인 과정을 거쳐 저장하면 돼.

아래는 형식 예시야. 수치는 실제 강아지에 대한 판단이 아니고, `<관찰 UUID>`는 그 강아지의 CONFIRMED 관찰 ID로 바꿔야 해.

```json
{
  "expectedRevision": 0,
  "schemaVersion": 1,
  "source": "SHELTER",
  "evidenceObservationIds": ["<관찰 UUID>"],
  "settings": {
    "actions": {
      "IDLE":     {"weight": 40, "speedTilesPerSecond": 0,   "minDurationMs": 2000, "maxDurationMs": 5000, "cooldownMs": 1000},
      "WALK":     {"weight": 40, "speedTilesPerSecond": 0.8, "minDurationMs": 2000, "maxDurationMs": 5000, "cooldownMs": 1000},
      "RUN":      {"weight": 20, "speedTilesPerSecond": 1.8, "minDurationMs": 1000, "maxDurationMs": 3000, "cooldownMs": 5000},
      "SNIFF":    {"weight": 20, "speedTilesPerSecond": 0,   "minDurationMs": 2000, "maxDurationMs": 5000, "cooldownMs": 5000},
      "TAIL_WAG": {"weight": 10, "speedTilesPerSecond": 0,   "minDurationMs": 1000, "maxDurationMs": 3000, "cooldownMs": 5000},
      "BACK_OFF": {"weight": 10, "speedTilesPerSecond": 0.6, "minDurationMs": 500,  "maxDurationMs": 1500, "cooldownMs": 3000},
      "SIT":      {"weight": 10, "speedTilesPerSecond": 0,   "minDurationMs": 3000, "maxDurationMs": 8000, "cooldownMs": 5000},
      "LIE_DOWN": {"weight": 5,  "speedTilesPerSecond": 0,   "minDurationMs": 5000, "maxDurationMs": 12000,"cooldownMs": 10000}
    },
    "approachDistanceTiles": 4,
    "personalSpaceTiles": 1.5,
    "reactionDelayMs": 1500,
    "ballPlay": {"chaseEnabled": true, "returnEnabled": true, "reactionDelayMs": 800}
  }
}
```

PUT과 확인 성공은 200이며 다음 관리 응답을 돌려줘. `settings`에는 저장한 전체 설정이 들어가.

```text
{data:{dogId, schemaVersion, revision, settings, source, status,
       evidenceObservationIds, confirmedBy, confirmedAt, updatedAt}}
```

## 숫자의 뜻과 범위

| 항목 | 범위 | 앱에서의 뜻 |
| --- | --- | --- |
| `weight` | 정수 0~100 | 지금 가능한 동작 사이의 상대 선택 비중. 합계 100일 필요 없음. 0은 해당 동작 사용 안 함 |
| `speedTilesPerSecond` | 0~6, 소수 둘째 자리까지 | **맵 한 타일/초** 단위의 이동 속도. 실제 강아지의 속도나 애니메이션 FPS가 아님 |
| `minDurationMs` | 정수 500~30,000 | 동작 유지 시간의 하한 |
| `maxDurationMs` | 정수 500~60,000, min 이상 | 동작 유지 시간의 상한 |
| `cooldownMs` | 정수 0~120,000 | 해당 동작 종료 후 다시 선택하기까지의 최소 대기 |
| `approachDistanceTiles` | 0~12, 소수 둘째 자리까지 | 방문자가 이 반경에 들어오면 접근을 고려. 0이면 자발적 접근 없음 |
| `personalSpaceTiles` | 0~12, 소수 둘째 자리까지, 접근 반경 이하 | 접근할 때 멈추는 거리. 이보다 가까워지면 BACK_OFF를 고려 |
| `reactionDelayMs` | 정수 0~10,000 | 방문자에 대한 반응을 기다리는 시간 |
| `ballPlay.reactionDelayMs` | 정수 0~10,000 | 공을 던진 후 반응까지 기다리는 시간 |

IDLE과 WALK 비중은 각각 1 이상이어야 해. WALK·RUN·BACK_OFF의 이동 속도는 0보다 크고, 나머지 5종은 0이어야 해. RUN은 WALK 이상 속도를 사용해. 모든 동작의 설정을 보내되 안 쓰는 동작은 weight를 0으로 두면 돼.

`chaseEnabled=false`면 공을 쫓지 않고, 이때 `returnEnabled=true`는 허용하지 않아. 공을 쫓을 때 RUN 비중이 0이면 WALK를 써. returnEnabled가 켜져 있으면 공 획득 뒤 입 위치에 공을 붙여 돌아오고, 꺼져 있으면 가져오기 단계는 생략해. 이동·공 충돌과 복귀 경로는 앱에서 처리해.

## 앱 조회 응답과 기본 설정

```text
{data:{dogId, schemaVersion:1, basis:"CONFIRMED" | "DEFAULT",
       revision:2 | null, settings:{...위 형식...}}}
```

공개 API에는 관찰 내용·관찰 ID·확인자·AI 출처·초안 상태를 넣지 않아. 승인·공개된 보호소의 공개 강아지 중 AVAILABLE / IN_PROGRESS만 조회돼. 비공개·보관·입양 완료·진행 중지·없는 ID는 404야.

설정이 없거나 초안이거나, 확인 후 근거가 철회되거나, 저장된 형식이 지원되지 않으면 `basis:DEFAULT`, `revision:null`로 반환해. 기본값은 IDLE 70 / WALK 30 / 나머지 0, 접근·거리 0, 공놀이 꺼짐이야. 이동 속도는 WALK 0.8 / RUN 1.8 / BACK_OFF 0.6이며 RUN과 BACK_OFF는 비중 0이라 재생하지 않아. 유지 2~5초·재선택 대기 2초·반응 대기 1초를 사용해. 기본 설정은 DB에 자동 저장하지 않아.

같은 강아지의 CONFIRMED 관찰 ID를 최대 20개 연결할 수 있어. 초안에는 빈 배열도 가능하지만 확인하려면 1개 이상 필요해. 저장과 확인 시 근거를 재검사하고, 앱 조회도 현재 근거를 검사해. 근거가 철회되면 설정 행은 검토용으로 유지하면서 공개 조회는 기본값으로 내려가. 저장·확인 과정에서 다른 강아지 기록을 연결하면 409이고 DB 외래 키도 교차 연결을 막아.

앱은 맵 진입·복귀와 새로고침 때 재조회하고, 공개 404면 해당 강아지를 맵에서 내려줘. Cache-Control은 no-store야. 이미 받은 설정을 서버가 앱에 즉시 회수하는 실시간 채널은 없어.

## 프론트에서 재생할 때

- 동작 키는 IDLE / WALK / RUN / SNIFF / TAIL_WAG / BACK_OFF / SIT / LIE_DOWN 그대로 사용해. 스프라이트 프레임·발 기준점·FPS는 에셋 정의에서 가져와.
- 상황에 맞는 동작을 먼저 고르고 그 안에서 weight를 적용해. BACK_OFF는 방문자가 개인 거리 안에 있을 때, TAIL_WAG는 관찰에 맞는 방문자·놀이 반응에 사용해. weight 0인 동작은 사건이 있어도 선택하지 않아.
- 지속 시간과 cooldown을 지켜 매 프레임마다 다시 선택하지 않도록 해. 가능한 동작이 없으면 IDLE을 유지해. 공놀이 중에는 배회 선택을 멈추고, 끝나면 기본 선택으로 돌아와.
- SIT·LIE_DOWN의 진입·유지·일어나기와 충돌 처리는 앱에서 구현해. **방문자 캐릭터의 10초 정지 후 SIT는 별도 규칙**이야.
- 서버가 준 수치는 동작 연출용이야. 동작 하나를 성격 진단이나 실제 행동 보장으로 표시하지 않아.

## 오류

공통 오류는 `{code,message,requestId}`이며 기존 인증 API의 401·403도 적용돼.

| HTTP / code | 처리 |
| --- | --- |
| 400 INVALID_BEHAVIOR | UUID·전체 필드·8개 키·수치·버전·근거 배열 확인 |
| 400 INVALID_REQUEST / 415 UNSUPPORTED_MEDIA_TYPE | JSON / Content-Type 확인 |
| 403 FORBIDDEN / ACCOUNT_DISABLED / ACCOUNT_NOT_REGISTERED | 계정과 승인된 보호소 소속 확인 |
| 404 DOG_NOT_FOUND | 공개 맵에서 강아지 제거 |
| 404 BEHAVIOR_NOT_FOUND | 확인 전에 설정부터 저장 |
| 409 STALE_RESOURCE / WRITE_CONFLICT | 새로 조회하고 수정 내용 재검토 |
| 409 INVALID_BEHAVIOR_EVIDENCE | 같은 강아지의 현재 확인된 관찰을 선택 |
| 409 DOG_ARCHIVED | 보관된 강아지는 읽기만 가능 |
| 500 INTERNAL_ERROR | 재시도 안내. 성공·기본 설정으로 위장하지 않음 |

## DB와 적용 범위

V3는 `dog_behavior_profiles.revision`과 `dog_behavior_evidence`를 추가해. 기존 빈 초안은 그대로 보존하고, 새 근거 테이블도 RLS와 클라이언트 접근 차단을 적용해. 저장·확인은 기존 계정·소속·보호소 공유 잠금과 강아지 잠금 안에서 실행하며, 버전 검사를 통과한 요청만 저장해. 공개·관리 조회는 일관된 DB 스냅샷으로 설정과 근거를 함께 읽어.

실제 Supabase 프로젝트에는 이번 마이그레이션을 적용하지 않았어. V1 개발 DB는 기존 B-07의 V2와 이번 V3를 순서대로 적용해야 해. 적용할 대상·백업을 확인한 뒤 기존 Flyway 절차를 사용해. 새 환경변수나 외부 AI 키는 필요 없어.

로컬·CI 검증과 API 구현 범위이며, RN에서 8종 애니메이션을 재생하는 작업·AI 초안 자동 생성·실제 서버 배포는 별도야. 샘플 강아지에 확인된 행동을 임의로 등록하지 않았으므로 샘플 조회는 DEFAULT로 시작해.
