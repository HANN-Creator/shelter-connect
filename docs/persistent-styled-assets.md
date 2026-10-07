# 32px 4방향 에셋 영구 저장·조회 (B-40)

강아지에 사용할 에셋을 한 번 생성하고 품질 검수를 통과하면, 방문자는 저장된 에셋을 재사용한다. B-63부터 새 32px 일반 작업의 기본 도트와 최종 팩은 [품질 통과 자동 승인](automatic-quality-approval.md)을 사용한다. 기존 Spring 서버의 작업 큐에 허가된 사진+스타일 파이프라인을 연결한다. FastAPI 서버를 별도로 운영할 필요는 없다.

## 저장과 생성 범위

- 파일: Supabase **비공개** `dog-assets` 버킷. `dogId/jobId/native-32/directions/{direction}.png` 4개, `sheets/{action}-{direction}.png` 선택 행동 수 × 4개(12~32개).
- DB: 기존 `asset_jobs`에 입력 특징과 기준 이미지 검토 기록, V13의 `behavior_plan`에 선택 스냅샷, V12의 `styled_asset_steps`에 13~33단계 진행·PixelLab 작업 ID·파일 키·SHA-256을 저장한다.
- PixelLab 완료 응답은 Storage 업로드 전에 DB에 임시 저장한다. 업로드 성공 후 임시 이미지 응답은 제거하고 파일 정보만 남긴다.
- 생성: `create-character-pro`의 4방향 기준 이미지 + `animate-pixminimax`의 기본3개 + 특징에 맞는 추가 행동(최대8개) × 4방향. 프레임은 32×32 원본이며 각 시트는 9프레임 288×32다. 리사이즈·블러·팔레트 변환을 하지 않는다.
- 버전: `cozy32-photo-style-v1`. [사진+스타일 규칙](styled-dog-pipeline.md)의 동일 Python payload builder와 승인 스타일을 JAR에 포함한다.
- 기존 사진 자동 등록 경로와 64px 하네스는 그대로 유지한다. **새 32px 경로는 아래 명시 요청 API를 사용한다.** 전체 보호 동물 자동 생성은 수행하지 않는다.

## 담당자 흐름

해당 보호소의 활성 담당자 또는 운영자만 요청·검토할 수 있다. 사진의 사용 허가와 PixelLab 전송 허가가 선행되어야 한다. `traits`는 실제 원본 사진을 확인한 설명이어야 하며, 서버에 저장된 PNG의 SHA-256과 정규화된 얼굴 영역을 함께 보낸다. 직접 사진 업로드 응답 및 담당자 사진 목록의 `sourcePhotoSha256`을 사용한다. 업로드 전 파일은 서버의 메타데이터 제거·PNG 변환으로 바이트가 달라질 수 있다. 외부 등록 사진은 운영 도구에서 저장된 원본 해시를 확인한다.

0. 특징이 있으면 Luna 행동 초안을 생성하고 확인한다. [B-42 선택 흐름](trait-selected-sprites.md)을 따른다. 미확인/특징 없음은 기본3개다.
1. `POST /v1/shelter-admin/dogs/{dogId}/styled-assets` → 202, 작업 ID.
2. `GET /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}`로 상태 조회.
3. 새 작업은 기준 도트의 [눈 표현 검사와 최대2회 보완](seed-eye-quality.md)을 먼저 수행한다. 해시와 모든 검수 근거가 통과하면 `seedReview.actor=SYSTEM`으로 자동 승인하고 행동 생성을 시작한다. 불합격·불확실은 `SEED_REVIEW`에 남으며 `GET .../{jobId}/preview`로 확인한다.
4. 각 행동·방향 시트는 Luna·픽셀 검사와 최대2회 자동 보완을 수행한다. 모든 선택 시트가 통과하면 `qualityApproval.actor=SYSTEM`으로 `APPROVED`. 실패가 남으면 `REVIEW`이며 공개되지 않는다. [자동 검수·보완](sprite-quality-repair.md) 참조.
5. `seed-review`와 `review`의 `decision`, 20자 이상 `note`, `expectedSeedHashes` 요청은 과거 수동 작업과 예외 처리용으로 유지한다. 정상 새 작업에서는 두 승인 요청이 필요 없다. 사진 허가·강아지·보호소 공개 조건도 만족해야 공개 조회가 가능하다.

요청 예시 (실제 사진 해시와 설명으로 교체):

```json
{
  "photoId": "00000000-0000-4000-8000-000000000001",
  "traits": {
    "sourcePhotoSha256": "사진 파일의 실제 SHA-256 64자리",
    "faceBox": [0.35, 0.245, 0.55, 0.48],
    "identityDescription": "Tan puppy, floppy dark ears, charcoal muzzle and sable back; no white blaze.",
    "motionDescription": "Same compact tan sable puppy; preserve floppy ears and dark muzzle.",
    "rearDescription": "Dark sable back, tan paws, floppy ears, long dark tail; face hidden.",
    "seed": 20261011,
    "reviewNote": "원본 사진과 얼굴 영역을 확인했습니다. 보이지 않는 뒷면 무늬는 추정입니다."
  }
}
```

검토 거절은 `REJECT`. 사진 특징이나 seed를 바꾸면 새 요청으로 간주하여 새 비용이 발생할 수 있다. 같은 사진·버전·특징·확인된 행동 버전으로 요청하면 JSON 키 순서나 검토 메모가 달라도 기존 작업을 반환한다.

## 프론트 연결

`GET /v1/dogs/{dogId}/assets`는 기존과 같은 공개 읽기 API다. 이 요청은 생성·등록·PixelLab 호출을 하지 않는다. 검토 전 또는 허가 철회 후에는 404다.

- `schemaVersion: 1`, `frameSize: {width:32,height:32}`, `anchorPixels: {x:16,y:30}`, `sampling: nearest`.
- `mapDirections.DOWN|UP|LEFT|RIGHT` 각각 `availableActions`에 있는 동작만 제공. 기본 `IDLE`, `WALK`, `SIT`; 선택 `RUN`, `SNIFF`, `TAIL_WAG`, `BACK_OFF`, `LIE_DOWN`.
- 각 행동에 `spritesheetUrl`, `sha256`, 9개의 `frames`, `loop`, `holdLastFrame`, `returnToIdle`, `worldMotion`을 제공한다.
- `generationPlan`은 생성 당시 선택 목록·버전·예상 요청 수. 공개 응답의 `behavior`는 현재 유효한 설정과 실제 시트의 교집합이며 `interactions`를 포함한다. 이전 전체33단계 팩은 generationPlan이 없을 수 있다.
- `animations`는 호환용 RIGHT 별칭이다. 새 프론트는 `mapDirections`를 우선 사용한다.
- WALK/RUN은 보는 방향으로, BACK_OFF는 반대로 이동한다. 실제 이동 거리·충돌·속도는 프론트가 결정한다. SIT/LIE_DOWN은 마지막 자세를 유지하고 `REVERSE_FRAMES`로 복귀한다.
- URL은 60초 만료 서명 링크다. `expiresAt` 전에 이 조회 API를 다시 호출한다. 이미지 생성 요청을 재호출하지 않는다. 앱 복귀 시 만료 여부도 확인한다.
- 기존64/`variants.MAP_32` 응답도 유지한다. 행동의 존재는 실제 강아지 성격의 근거가 아니며, 노출 빈도는 확인된 행동 설정을 사용한다.

## 비용·재시작·실패

유료 POST 전에 DB에 예약과 요청 시도 이력을 기록한다. 기존64 파이프라인과 같은 `asset_submissions`에 기록하며 자체 일일 횟수 제한은 없다. 요청 수는 PixelLab의 청구 Generations와 다르다. 33회 요청이 반드시 33 Generations인 것은 아니다.

- `ASSET_GENERATION_ENABLED`, `ASSET_WORKER_ENABLED`, `PIXELLAB_API_KEY`, `SUPABASE_SECRET_KEY`는 기존 설정을 사용한다.
- B-41에서 `ASSET_DAILY_REQUEST_LIMIT` / `app.assets.daily-requests`를 제거했다. 기존 설정이 남아 있어도 무시한다. 한 마리의 13~33개 요청을 날짜별로 나누지 않고, 원본 검토와 각 요청의 완료를 기다리며 순서대로 처리한다. PixelLab 자체 한도·과금은 그대로 적용된다.
- 이전 `DAILY_REQUEST_LIMIT` 때문에 다음 날로 미뤄진 실행 가능 작업은 다음 워커 실행에서 다시 처리한다. 활성 lease, 다른 예약 시간, 검토·실패·접수 불명 상태는 그대로 존중한다.
- 접수된 작업 ID가 있으면 재시작 후 조회만 재개한다. Storage 장애는 DB에 임시 보관한 완료 응답부터 저장을 재개한다.
- 요청 직후 연결이 끊겨 접수 여부가 불명확하면 `OUTCOME_UNKNOWN`으로 정지한다. 자동 재결제하지 않는다.
- 운영자만 `POST /v1/operations/styled-asset-jobs/{jobId}/recover`를 사용한다. 결과 불명 시 PixelLab 대시보드에서 확인한 `providerJobId`가 필수다. 공급자가 실패를 확정한 작업만 명시적으로 새 요청을 허용하고 이전 ID·요청 해시를 이력에 남긴다. 일반 저장 실패는 `{}`로 기존 결과부터 재개한다.
- 현재 회복 API에 넣는 공급자 ID의 개체 일치는 운영자가 확인해야 한다. 기준 프레임 및 최종 품질 검수 통과 없이 자동 공개되지 않는다.

## 적용과 확인

V12는 테이블 1개와 기존 작업 컬럼 2개, V13은 선택 계획 스냅샷 컬럼 1개를 추가한다. 기존 33단계 작업은 계획 변경 없이 계속 조회·재개된다. 고객용 Supabase `anon`/`authenticated`는 새 테이블에 접근할 수 없다. 기존 최소 권한 `shelter_runtime`만 서버에서 사용한다.

단위 검사: 사진·스타일 payload, 프레임 픽셀 보존, 실제 HTTP 응답/ZIP/9프레임 검증. PostgreSQL 검사: 동시 요청·선점, 재시작·접수 불명, 저장 장애 복구, 검토 전 차단, 공개 후 재사용, 허가 철회, 전역 예산과 RLS. 실제 개발 DB·배포·저장소 적용 결과는 B-40 노션 카드와 PR 기록을 기준으로 확인한다.

B-43: V14와 `sprite-quality-v1` 자동 검수·보완을 추가했다. 기존 작업은 `/repair` 명시 요청 시 검사하며, 정상 시트와 기준 이미지는 재사용한다. [세부 계약](sprite-quality-repair.md).

B-54: 새 작업의 `qualityPolicy.idleRepair=calm-idle-edit-v1`은 반복 `IDLE_MOTION`의 마지막 보완을 9프레임 전체 편집으로 전환한다. 기존 작업 정책과 2회 보완 예산은 유지하며, 원본·복원 결과 모두 통과해야 한다. [처리·검증 경계](idle-edit-repair.md).

B-55: 새 작업의 `qualityPolicy.marginRepair=frame-margin-edit-v1`은 일반 동작에서도 반복된 `CANVAS_CLIPPING`의 마지막 보완을 전체 프레임 편집으로 전환한다. 요청 행동과 방향을 유지하고 기존 IDLE·LOW TAIL_WAG 편집이 우선한다. 기존 정책·예산과 원본 검수 경계는 유지한다. [처리·검증 경계](motion-margin-edit.md).
