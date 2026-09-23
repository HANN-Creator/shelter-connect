# 사진·특징 입력 → 강아지 에셋

B-19 · [작업 카드](https://app.notion.com/p/3e45b2d1a55f8081a888cec58625f09c)

사진은 외형을 만드는 데 쓰고, 특징은 보호소가 확인한 관찰 기록에서 가져온다. Luna가 특징을 분류하면 서버가 정해진 행동 프리셋에 매칭한다. PixelLab과 모션 하네스가 이미지를 만들고, 승인된 파일을 앱에 전달한다.

B-25에서 사진 외형 분석도 연결했다. 등록 사진 한 장으로 Luna가 외형 설명과 얼굴 영역을 추출하고, 서버가 전체 사진·얼굴 사진을 PixelLab에 전달한다. 성격·행동 분석과는 별개이며 [외형 분석 명세](photo-appearance-generation.md)를 따른다.

## 연결 순서

1. 승인된 보호소 계정으로 강아지와 관찰 기록을 등록하고 관찰을 `CONFIRMED`로 확인한다.
2. 아래 행동 초안 API에 해당 관찰 ID를 보낸다. 결과의 근거와 설정을 검토하고 필요하면 기존 행동 수정 API로 고친다.
3. `POST /v1/shelter-admin/dogs/{dogId}/behavior/confirmation`에 현재 `expectedRevision`을 보내 행동 설정을 확인한다.
4. 운영자가 `sourceKind=SHELTER`, 이미지 가공·OpenAI 외형 분석·PixelLab 전송을 허용한 출처를 등록한다. 실제로 확인한 범위를 허가 기록에 적는다. 크롤링 허가는 필요 없다.
5. 사진을 업로드한다. 출처의 `autoGenerate`와 서버 자동 등록 설정이 켜져 있으면 생성 작업이 함께 반환된다. 꺼져 있으면 `REGISTERED`만 반환한다.
6. 생성 작업을 조회한다. `RIG_REVIEW`에서 체형을 확인한 뒤 `rig/confirm`을 호출한다. `REVIEW`에서 동작을 확인한 뒤 `review`로 승인한다.
7. 앱은 `GET /v1/dogs/{dogId}/behavior`와 `GET /v1/dogs/{dogId}/assets`를 연결한다. 이미지·프레임·이동 규칙은 [재생 명세서](dog-action-playback-spec.md)를 따른다.

행동 설정을 나중에 바꿔도 이전 생성 작업은 바뀌지 않는다. 새 확인 버전으로 만들려면 등록된 `photoId`로 기존 생성 API를 호출한다. 이전 승인본은 새 결과가 승인될 때까지 유지된다. 생성 중 체형 확인과 최종 승인은 자동으로 건너뛰지 않는다.

## 특징 분석

승인 보호소의 소속 강아지만 가능하다. Supabase access token을 `Authorization: Bearer ...`로 전달한다.

`POST /v1/shelter-admin/dogs/{dogId}/behavior/suggestions`

```json
{
  "clientRequestId": "02300000-0000-4000-8000-000000000001",
  "expectedRevision": 0,
  "evidenceObservationIds": ["02300000-0000-4000-8000-000000000002"]
}
```

처음 설정할 때 버전은 0, 기존 설정이 있으면 관리 행동 조회의 버전을 사용한다. 관찰은 같은 강아지의 확인된 기록 1~20개, 합계 20,000자 이하다. 응답은 `{data:{id,dogId,status,failureCode,result,createdAt}}`이며 `result`에는 `profile`, 근거가 있는 `traits`, `requiresConfirmation:true`가 들어간다. 상태는 `PENDING`, `COMPLETED`, `FAILED`다. HTTP 200이어도 작업 상태를 확인한다.

연결이 끊기면 같은 요청 ID와 같은 본문으로 다시 보내거나 `GET /v1/shelter-admin/dogs/{dogId}/behavior/suggestions/{suggestionId}`를 조회한다. 같은 ID로 다른 내용을 보내면 409다. 실패한 요청의 ID는 AI를 다시 호출하지 않는다. 실패 이유를 확인한 뒤 사용자가 명시적으로 재시도할 때만 새 ID를 만든다. 처리 기한을 넘긴 요청은 `AI_OUTCOME_UNKNOWN`으로 남으며 자동 재호출하지 않는다.

| 근거 태그 | 설정되는 행동 |
| --- | --- |
| RUNNER | RUN 비중 30 |
| SNIFFER | SNIFF 비중 30 |
| FRIENDLY | TAIL_WAG 비중 30, 접근 거리 4타일 |
| CAUTIOUS | BACK_OFF 비중 30, 개인 거리 2타일·반응 대기 1.5초 |
| RESTFUL | LIE_DOWN 비중 30 |
| BALL_CHASER | RUN 40·SNIFF 30, 공 추적 허용 |
| BALL_RETURNER | RUN 40·SNIFF 30, 공 추적·가져오기 허용 |

공통 기본 비중은 IDLE 60·WALK 30·SIT 10이다. CAUTIOUS와 FRIENDLY가 함께 있으면 거리 설정은 조심스러운 쪽을 우선한다. 태그가 없으면 공통 설정만 초안으로 만든다. 반환된 관찰 ID와 인용문을 서버가 검증하고, AI 응답 후 근거·권한·버전을 다시 확인한다. 분류의 의미가 맞는지는 보호소가 최종 확인한다.

`AI_ENABLED=true`, 유효한 `OPENAI_API_KEY`가 필요하다. 기존 대화 모델 설정을 사용한다. `BEHAVIOR_AI_DAILY_LIMIT=20`은 UTC 하루 전체 보호소의 초안 요청 상한이며 실패한 시도도 포함한다. 대화 호출의 한도와는 별도다.

## 사진 직접 업로드

`POST /v1/shelter-admin/dogs/{dogId}/photos` · `multipart/form-data`

| 파트 | 형식 | 내용 |
| --- | --- | --- |
| file | PNG/JPEG 바이너리 | 8MB 이하, 가로·세로 16~8192, 총 1600만 픽셀 이하 |
| metadata | application/json | 아래 JSON |

```json
{
  "clientUploadId": "02300000-0000-4000-8000-000000000003",
  "permissionId": "02300000-0000-4000-8000-000000000004",
  "rightsConfirmed": true,
  "rightsNote": "확인한 사진 게시·가공·외부 전송 허가의 근거"
}
```

현재 업로드는 `ASSET_GENERATION_ENABLED=true`와 비공개 Storage 연결을 필요로 한다. 서버는 사진의 메타데이터를 제거하고 긴 변을 최대 2048픽셀로 줄여 PNG로 저장한다. 경로·버킷·외부 URL은 클라이언트가 지정하지 않는다. 같은 업로드 ID·정규화된 사진·허가 내용은 같은 사진으로 처리한다. 응답은 `{data:{photoId,status:"REGISTERED"}}` 또는 `{data:{photoId,job}}`이다.

저장 중 오류가 나면 같은 요청을 다시 보낼 수 있다. 진행 중인 같은 요청은 409 `UPLOAD_IN_PROGRESS`, 다른 사진·허가 내용이면 409 `UPLOAD_ID_CONFLICT`다. 업로드 중 소속이나 허가가 철회되면 공개하지 않는다. 실패한 업로드는 `UNKNOWN` 권리 상태로 남아 원본 조회·생성에서 제외된다.

같은 사진·확인된 행동 버전의 생성 작업이 이미 있으면 기존 작업을 반환한다. 재요청 때문에 워커의 작업 행을 갱신하거나 새 생성 요청을 보내지 않는다. 워커가 작업을 처리하는 중에도 완료된 업로드를 다시 조회할 수 있다.

`GET /v1/shelter-admin/dogs/{dogId}/photos?limit=20&cursor=...`는 `{data:[{id,rightsStatus,sortOrder,uploadStatus}],nextCursor}`를 반환한다. 최대 50건, 마지막 응답의 커서를 그대로 사용한다. 진행 중·실패한 예약도 표시할 수 있으며 원본 사진 주소는 포함하지 않는다.

## 공통 행동과 공놀이

신규 생성 목록은 IDLE/WALK/SIT + 대표 행동 최대 2개다. 공놀이가 켜져 있고 비중이 양수인 RUN/SNIFF는 대표 행동 슬롯을 우선 사용한다. 나머지는 비중 순이다. 이미 확인된 설정의 SIT 비중이 0이면 시트는 생성해도 앱에서는 자발적으로 선택하지 않는다.

`behavior.data.interactions.BALL_CHASE`에 공 추적 → 가까이 걷기 → 냄새 맡기 → 선택적 복귀 → 대기 단계가 내려간다. 새 공놀이 시트를 만들지 않고 RUN/WALK/SNIFF/IDLE을 사용한다. 앱이 이 단계대로 이동과 공 표시를 구현한다.

## 오류와 DB 적용

공통 오류 형태는 `{code,message,requestId}`다. 인증·소속 오류 401/403, 잘못된 입력 400, 없는 초안 404, 버전·근거·허가 충돌 409, 파일 초과 413, 지원하지 않는 미디어 415, AI 한도 429, 연결 비활성화 503을 처리한다. 공급자 오류는 작업의 `failureCode`도 확인한다.

[V6](../backend/src/main/resources/db/migration/V6__asset_input_workflow.sql)는 행동 초안 요청과 사진 업로드 예약 테이블 2개를 추가한다. 두 테이블 모두 RLS와 클라이언트 직접 접근 차단을 적용한다. 기존 V1~V5 다음에 적용하며 기존 사진의 권리나 행동 승인을 변경하지 않는다. 실제 개발 DB·배포 적용 결과는 B-19 카드와 PR에 기록한다.

크롤러, 실사진 사용 허가 확보, RN 화면과 재생 구현은 이 서버 API에 포함되지 않는다.
