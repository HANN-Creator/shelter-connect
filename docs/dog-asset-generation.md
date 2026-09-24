# 사진 → 도트 강아지 · 특성별 동작

B-15 · [노션 카드](https://app.notion.com/p/3e35b2d1a55f803c9030e98a21d336e5)

PixelLab을 사용한다. 사진을 참고해 기준 도트를 만들고, 공통 IDLE/WALK/SIT와 관찰 기록에 맞는 대표 행동을 최대 두 개 더 생성한다. 서버에서 한 번 만들어 저장한 PNG를 앱이 재생하는 구조다. 움직일 때마다 AI를 호출하지 않는다.

B-16에서 [특성별 행동 선택·모션 하네스·체형 검토](motion-harness-pipeline.md)를 연결했다. 생성 작업 저장, PixelLab 호출, 비공개 Storage 저장, 보호소 검토, 공개 조회를 제공한다. 기본 스위치는 OFF이며 실제 배포·DB 적용 결과는 해당 PR과 노션 카드에 기록한다. 크롤러는 포함하지 않는다.

## 기준 캐릭터의 그림체

B-25부터 사진을 Luna로 분석해 구체적인 외형 설명과 얼굴 참고 사진을 준비한다. PixelLab에는 전체 사진·얼굴 사진·그림체 참조를 각각 전달한다. [외형 분석 흐름과 실패 코드](photo-appearance-generation.md)를 참고한다. 기존 사진 등록 API의 요청 형식은 같다.

B-24에서 기존 두부를 생성할 때 사용한 `cozy-dog-v1` 그림체 참조를 서버의 BASE 요청에도 연결했다. 사진은 각 강아지의 털색·무늬·귀·주둥이·꼬리를, 별도 스타일 이미지는 도트 표현·외곽선·디테일·명암을 결정하도록 요청한다. 참고 이미지의 색을 복사하는 옵션은 끄고, 머리가 조금 큰 귀여운 비율과 자연스러운 네 발 자세를 명시한다. [참고 이미지와 출처](../backend/src/main/resources/sprite-style/README.md)를 서버 실행 파일에 함께 넣으므로 별도 키나 외부 다운로드는 필요 없다.

이 설정은 배포 이후 새로 제출되는 BASE 요청에 적용된다. 이미 만들어진 캐릭터와 진행 중인 동작의 기준 이미지는 그대로 둔다. PixelLab 동작은 해당 강아지의 BASE를 참조하며 하네스도 같은 원본으로 렌더링한다. API·DB 구조와 행동 종류는 바뀌지 않는다. 기존 작업을 일괄 재생성하지 않는다.

그림체 참조는 생성 방향을 맞추는 장치이며 결과 일치를 보장하지 않는다. `RIG_REVIEW`에서 원본 사진의 외형과 그림체를 함께 확인해야 한다. 서버 연결을 확인하는 가상 그림은 디자인 승인용 샘플과 구분한다.

## 생성 규격

기준 도트는 `generate-image-v2`, 동작은 Animation tools의 **Animate with text (Pro)**인 `animate-with-text-v2`를 사용한다. 같은 기준 도트, 64×64, 투명 배경, 오른쪽 3/4 방향을 유지한다. Pro는 이 크기에서 동작별 16프레임을 반환한다. 현재 생성 버전은 `pixellab-harness-v3`다. 아래 표는 선택 가능한 행동이며 전부 생성하는 목록은 아니다. WALK/RUN은 로컬 하네스가 48프레임, BACK_OFF는 24프레임으로 만든다.

| 키 | 동작 | 프레임당 시간 | 재생 |
| --- | --- | --- | --- |
| IDLE | 서서 숨 쉬고 살펴보기 | 140ms | 반복 |
| WALK | 네 발로 걷기 | 60ms | 반복 |
| RUN | 달리기 | 30ms | 반복 |
| SNIFF | 고개를 숙여 냄새 맡기 | 100ms | 반복 |
| TAIL_WAG | 꼬리 흔들기 | 90ms | 반복 |
| BACK_OFF | 바라보는 방향을 유지하며 물러서기 | 70ms | 반복 |
| SIT | 앉기 | 90ms | 마지막 자세 유지 |
| LIE_DOWN | 엎드리기 | 100ms | 마지막 자세 유지 |

앉기·눕기에서 일어나기는 같은 프레임을 역순으로 재생하는 `REVERSE_FRAMES`로 안내한다. 별도의 일어나기 이미지를 생성한 것은 아니다. 프레임마다 크기를 바꾸지 않고 발 기준점 `(32,60)`에 정렬한다. RUN의 공중 동작은 수직 이동을 보존한다. SIT·LIE_DOWN의 첫 장만 기준 도트로 고정하고, 반복 동작에는 멈춘 자세를 끼워 넣지 않는다.

PixelLab 16장·하네스 WALK/RUN 48장·BACK_OFF 24장으로 프레임 수가 맞는지, 투명도·64px 캔버스·잘린 외곽이 없는지 검사한다. 기계 검사는 해부학이나 자연스러운 걸음을 보장하지 않으므로 생성 결과는 `REVIEW`까지만 자동으로 진행된다. 보호소가 외형·동작을 확인해야 공개할 수 있다. 성격·행동 비중은 사진에서 추정하지 않고 기존 [행동 설정](dog-behavior-api.md)을 사용한다.

## 보호소 직접 등록

크롤링 없이 보호소가 사진과 확인된 특징을 넣는 API는 [사진·특징 입력 명세서](asset-input-workflow.md)에 있다. 행동 초안을 확인한 다음 사진을 업로드하면 최신 특성으로 생성 목록이 만들어진다.

## 허가받은 수집 데이터를 연결하는 순서

1. 운영자가 보호소와 출처별 허가 기록을 남긴다. 크롤링, 이미지 가공, PixelLab 전송을 따로 확인한다. 사진 게시 허가만으로 AI 전송을 허용하지 않는다.
2. 추후 수집기가 해당 출처의 사진을 `dog-photos` 비공개 버킷에 저장하고 `dog_photos`에 권리 확인 기록을 등록한다. 저장 키는 `{dogId}/...png` 또는 JPEG로 잡고 **덮어쓰지 않는다**. 사진 내용이 바뀌면 새 객체와 사진 ID를 만든다.
3. 저장이 끝나면 서버 내부 `AssetStore.photoStored(photoId, permissionId)`를 호출한다. 다른 서비스라면 운영자 인증이 있는 `POST /v1/operations/asset-imports`를 사용한다. 이 API는 임의 URL을 다운로드하지 않는다.
4. 출처의 `autoGenerate=true`와 서버의 생성·자동 등록 스위치가 모두 켜져 있으면 같은 트랜잭션에서 생성 작업을 만든다. 아니면 출처 연결만 저장한다. 스위치를 켠 뒤 같은 등록 이벤트를 다시 보내면 생성 작업이 만들어진다.
5. 워커가 기준 도트 → 체형 검토 → 선택한 동작을 순서대로 만들고 비공개 `dog-assets`에 저장한다. 같은 사진 ID·생성 버전·행동 설정 버전과 선택 목록의 중복 이벤트는 기존 작업을 돌려준다.
6. 보호소 담당자가 미리보기를 검토한다. 승인한 에셋만 공개 조회된다.

현재 수집기 자체·웹사이트 수집 주기·원격 사진 다운로드는 포함하지 않았다. 웹페이지 내용은 허가 기록이나 실행 지시로 사용하지 않는다. 일반 사용자는 출처 허가를 등록할 수 없고, 운영자도 보호소 소속 없이 검토 API를 사용할 수 없다.

## API 명세

모든 주소 앞에 `/v1`을 붙인다. 관리 요청은 Supabase access token을 `Authorization: Bearer ...`로 보낸다. 응답은 기존 API와 같은 `{ "data": ... }` 형식이며 `Cache-Control: no-store`다.

| 메서드 · 경로 | 권한 | 내용 |
| --- | --- | --- |
| POST `/operations/asset-permissions` | 운영자 | 보호소·출처 허가 기록. 201 |
| DELETE `/operations/asset-permissions/{id}` | 운영자 | 허가 철회·관련 작업 취소·공개 중단. 기록은 보존. 204 |
| POST `/operations/asset-imports` | 운영자 | 저장된 사진에 허가 연결, 조건 충족 시 자동 생성. 200 |
| POST `/operations/asset-jobs/{id}/retry` | 운영자 | 명확히 실패한 작업의 실패 단계만 다시 요청. 유료 호출 가능. 200 |
| POST `/operations/asset-jobs/{id}/reconcile` | 운영자 | 접수 여부 불명 작업에 실제 PixelLab 작업 ID 연결. 새 생성 요청 없음. 200 |
| POST `/shelter-admin/dogs/{dogId}/assets` | 승인 보호소 소속 | 연결된 사진으로 수동 생성 요청. 202 |
| GET `/shelter-admin/dogs/{dogId}/assets/{id}` | 해당 보호소 소속 | 선택된 단계의 진행 상태·체형 확인 여부·실패 코드. 200 |
| GET `/shelter-admin/dogs/{dogId}/assets/{id}/preview` | 해당 보호소 소속 | 완성된 초안 미리보기용 manifest. 200 |
| POST `/shelter-admin/dogs/{dogId}/assets/{id}/review` | 해당 보호소 소속 | `APPROVE` 또는 `REJECT`. 200 |
| GET `/dogs/{dogId}/assets` | 공개 | 공개 중인 강아지의 승인된 manifest. 없으면 404 |

허가 등록 예시다. ID와 허가 내용은 실제로 확인한 값으로 채운다. 아래 항목을 그대로 따라 쓴다고 허가가 생기는 것은 아니다.

```json
{
  "shelterId": "<shelter-uuid>",
  "sourceKey": "partner-feed-v1",
  "sourceKind": "CRAWL",
  "permissionNote": "확인한 허가 문서·범위·담당자·기간에 대한 내부 기록",
  "crawlAllowed": true,
  "derivativesAllowed": true,
  "pixellabAllowed": true,
  "autoGenerate": true
}
```

보호소 직접 등록은 `sourceKind: "SHELTER"`로 구분한다. `CRAWL`이면 `crawlAllowed`도 필요하다. 하나의 활성 출처 키는 보호소 안에서 유일하다. 철회한 허가를 다시 활성화하는 API는 없으며 새 허가를 기록한다. 이미 출처에 연결한 사진의 허가나 파일 경로는 조용히 교체하지 않는다.

```json
// POST /operations/asset-imports
{ "photoId": "<photo-uuid>", "permissionId": "<permission-uuid>" }
// POST /shelter-admin/dogs/{dogId}/assets
{ "photoId": "<photo-uuid>" }
// POST /shelter-admin/dogs/{dogId}/assets/{id}/review
{ "decision": "APPROVE" }
// POST /operations/asset-jobs/{id}/reconcile
{ "providerJobId": "<existing-pixellab-job-uuid>" }
```

`retry`는 요청 본문이 없다. `OUTCOME_UNKNOWN`은 재생성으로 넘길 수 없다. 운영자가 PixelLab 작업 내역과 요청 기록을 대조한 뒤 실제 작업 ID를 `reconcile`로 연결한다. 확인되지 않은 작업을 재시도하도록 자동 해제하지 않는다. `REJECTED` 결과는 공개하지 않으며, 외형 수정·일부 동작 편집 UI는 후속 작업이다.

상태는 `QUEUED → RUNNING → RIG_REVIEW → QUEUED → RUNNING → REVIEW → APPROVED/REJECTED`다. 체형 조회·확정 API는 [하네스 문서](motion-harness-pipeline.md)에 있다. 오류 상태는 `FAILED`, `OUTCOME_UNKNOWN`, `CANCELLED`로 나뉜다. 조회에는 BASE와 선택된 3~5개 행동의 상태가 함께 들어간다. 사진 원본 주소나 생성 업체 키는 응답에 포함하지 않는다.

주요 오류는 400 `INVALID_ASSET_REQUEST`, 403 `FORBIDDEN`, 404 `ASSET_NOT_FOUND`, 409 `ASSET_PERMISSION_REQUIRED` / `ASSET_NOT_READY` / `ASSET_RETRY_NOT_ALLOWED`, 503 `ASSET_GENERATION_UNAVAILABLE`다. 동일 출처 키나 작업 ID 충돌은 409 `WRITE_CONFLICT`다.

## 프론트가 사용하는 manifest

각 행동을 언제 선택하고 어떻게 이동·종료할지는 [행동별 재생 명세서](dog-action-playback-spec.md)를 참고한다. 에셋 목록과 행동 설정의 비중을 함께 확인해야 하며, 시트가 존재한다고 항상 재생하는 것은 아니다.

```json
{
  "data": {
    "schemaVersion": 1,
    "id": "<job-uuid>",
    "status": "APPROVED",
    "provider": "PixelLab + motion harness",
    "availableActions": ["IDLE", "WALK", "SIT"],
    "fallbackAction": "IDLE",
    "behaviorRevision": 1,
    "frameSize": { "width": 64, "height": 64 },
    "anchorPixels": { "x": 32, "y": 60 },
    "facing": "right-three-quarter",
    "baseUrl": "<signed-base-url>",
    "expiresAt": "<UTC-time>",
    "animations": {
      "SIT": {
        "spritesheetUrl": "<signed-sheet-url>",
        "frameCount": 16,
        "loop": false,
        "holdLastFrame": true,
        "returnToIdle": "REVERSE_FRAMES",
        "frames": [{ "x": 0, "y": 0, "width": 64, "height": 64, "durationMs": 90 }]
      }
    }
  }
}
```

예시의 배열은 한 프레임만 표시했다. 실제로는 선택된 3~5개 동작이 온다. PixelLab 시트는 1024×64(16프레임), 하네스 WALK/RUN 시트는 3072×64(48프레임), BACK_OFF는 1536×64(24프레임)다. 기존 승인본은 이전 프레임 수를 유지한다. `availableActions`에 없는 행동은 `IDLE`로 대체한다. 앱은 64×64 영역을 잘라 재생하고 확대 시 nearest-neighbor를 사용한다. 서명 주소는 60초이므로 만료되면 manifest를 다시 조회한다. 이미 내려받은 파일 자체를 회수하는 기능은 아니다.

공개 요청은 강아지·보호소 공개 상태, 입양 상태, 사진 권리와 출처 허가를 재확인한다. Storage 서명 발급 뒤에도 다시 확인한다. 초안 검토는 공개 앱의 실제 사진 열람 조건과 별도다. 이 API는 원본 사진을 노출하지 않으며 기존 대화 후 사진 공개 규칙도 바꾸지 않는다.

## 서버 설정과 실패 처리

[설정 예시](../backend/.env.assets.example)를 참고한다. 세 스위치 모두 기본 false다.

- `ASSET_GENERATION_ENABLED`: PixelLab·Storage 사용과 수동 생성 요청 허용
- `ASSET_WORKER_ENABLED`: 저장된 작업을 5초 간격으로 처리
- `ASSET_AUTO_IMPORT_ENABLED`: 허가된 사진 등록 이벤트에서 자동으로 작업 생성
- `PIXELLAB_API_KEY`, `SUPABASE_SECRET_KEY`: 서버에만 보관
- `ASSET_STORAGE_BUCKET=dog-assets`, `PHOTO_STORAGE_BUCKET=dog-photos`: 둘 다 비공개 버킷, 서로 다른 이름
- `ASSET_DAILY_REQUEST_LIMIT=10`: UTC 기준 유료 요청 시도 수의 일일 상한. 금액·생성 단위 상한이 아니다. 명시적인 재시도도 센다.

DB는 [V4](../backend/src/main/resources/db/migration/V4__dog_asset_generation.sql)와 [V5](../backend/src/main/resources/db/migration/V5__motion_harness.sql)를 기존 V1~V3 다음에 Flyway로 적용한다. 새 테이블 5개 모두 RLS와 클라이언트 접근 차단을 적용한다. 기존 사진을 허가하거나 자동 생성 대상으로 바꾸는 데이터 변경은 없다. `exportSupabaseUpgrade`는 기존 V1→V3 전용 도구이므로 V4를 적용하지 않는다.

작업과 PixelLab ID를 DB에 저장한다. 네트워크 요청 중 DB 트랜잭션을 열어두지 않는다. 워커는 3분 동안 작업을 선점하고, 다른 서버가 동시에 같은 작업을 실행하지 못하게 한다. 유료 POST 전에 `SUBMITTING`을 먼저 저장한다. 접수 응답을 잃거나 이 상태에서 서버가 꺼지면 `OUTCOME_UNKNOWN`으로 멈춘다. 이미 받은 PixelLab ID가 있으면 상태 조회와 같은 Storage 경로 업로드만 재시도한다. 두 시간 넘게 완료를 확인하지 못한 작업도 운영자가 확인한다.

API·이미지 응답 크기를 제한하고, 사진은 PNG로 재인코딩해 메타데이터를 제거한다. 임의 외부 사진 URL이나 외부 signed URL을 따라가지 않는다. Storage 버킷이 공개돼 있으면 생성을 멈춘다.

현재 Render 무료 웹 서비스가 잠들면 워커도 처리하지 못한다. 큐는 DB에 남으므로 깨어난 뒤 이어갈 수 있지만, 정해진 시간 안에 계속 생성해야 한다면 상시 실행되는 워커가 필요하다. 이 작업에서는 배포 서비스나 요금제를 바꾸지 않았다.

## B-15 검증 기록과 다음 연결

Java 단위·HTTP 검사 281개와 PostgreSQL 통합 검사 216개, 빌드가 통과했다. 로컬의 일회용 PostgreSQL과 테스트 HTTP 서버에서 8종 생성 순서, 중복·동시 등록, 소속·운영자 권한, 허가 철회, 일일 제한, 서버 중단 후 불명 요청 차단, 기존 provider ID 복구, 저장소 재시도, 검토 전 공개 차단을 검사한다. 테스트에서는 실제 이미지 API나 Supabase를 호출하지 않는다.

실제 PixelLab 샘플은 별도로 생성했다. 기존 v3 8종과 Tier 2의 PixMiniMax·Pro 걷기를 비교했고, 기준 캐릭터 유지가 더 나은 Pro를 선택했다. 실제 샘플과 전달 ZIP은 `output/imagegen/pixellab-tier2-v2/` 아래에 두며 Git에서는 제외한다. 입력 사진과 API 키는 전달물에 넣지 않는다. 예전 OpenAI 비교는 [시험 기록](dog-asset-comparison.md)에 남겼다.

B-16 이후 남은 서비스 연결은 수집 허가 확보, 수집기의 사진 저장·등록 훅 호출, RN 화면에서의 재생·체형 검수다. 실제 개발 서버 적용 상태는 B-16 PR·카드에서 확인한다. 이번 에셋은 한 방향이며 4/8방향 생성은 포함하지 않는다.

공식 명세 확인: 2026-09-23. [PixelLab API](https://www.pixellab.ai/pixellab-api), [OpenAPI](https://api.pixellab.ai/v2/openapi.json). 사용량 단위와 청구 금액은 구분한다.
