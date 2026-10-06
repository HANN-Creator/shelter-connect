# B-53 기본 도트 학습 자료 등록

B-52는 서버가 생성한 도트의 자동 학습을 연결했다. 사용자가 승인한 로컬 도트와 사람이 발견한 AI 오탐을 실제 학습 자료로 등록할 수 없어서, 별도 등록 경로를 추가한다.

## API와 처리

`POST /v1/shelter-admin/dogs/{dogId}/styled-seed-examples`는 해당 보호소 STAFF/MANAGER만 호출한다. `metadata`는 application/json이며 `south`, `north`, `west`, `east` 파트는 각각 원본 32×32 투명 PNG다. 파일당 65,536바이트 이하이고 서버는 픽셀을 변환하지 않는다.

metadata 필드:

- `photoId`, `sourcePhotoSha256`: 기존에 사용 허가를 받은 사진과 저장된 원본의 SHA-256.
- `expectedSeedHashes`: 네 방향 PNG 바이트의 SHA-256 객체.
- `assessment`: `POSITIVE` 또는 `NEGATIVE`.
- `issues`: POSITIVE는 빈 배열, NEGATIVE는 눈 가독성·눈 스타일·눈 방향·외형·잘림 중 하나 이상. 기존 BASE 결함 코드만 받는다.
- `note`: 사람이 해당 평가를 내린 이유, 20–1000자.

사진·도트·공통 규칙이 같으면 기존 작업을 돌려주며 같은 도트에 대한 상반된 평가는 409로 막는다. 등록 후 Luna 검수는 기존 작업 큐가 수행한다. 응답은 기존 StyledAssetJob 형식이며 조회·미리보기·seed-review도 기존 경로를 쓴다.

## 학습 증거와 승인

- POSITIVE: Luna 통과 후 같은 원본 해시에 대해 기존 seed-review로 승인해야 정상 학습 예시가 된다. 사람이 정상이라고 지정했으나 Luna가 실패시킨 경우 학습 예시에 넣지 않고 검토를 기다린다.
- NEGATIVE: 사람이 알려준 결함을 학습 예시의 평가로 사용한다. Luna의 실제 판정은 단계의 `qualityReport` 및 예시의 `aiAssessment`에 그대로 남긴다. 예시에는 `assessmentSource=HUMAN_NEGATIVE_FEEDBACK`와 `humanAssessment`를 기록한다. AI가 실패했다고 허위로 기록하지 않는다.
- 규칙 후보는 기존 정답 비공개 재검증을 통과해야 활성화한다. 사람이 준 평가도 재검증 통과를 보장하지 않는다.
- 등록 자료의 `qualityPolicy.referenceOnly=true`와 `actionPlan=["BASE"]`는 고정이다. 승인 후에도 SEED_REVIEW에 남는다. PixelLab 요청·자동 보완·행동 생성·앱 공개를 막는다.
- 승인된 기존 이미지와 기존 생성 작업의 보완 예산은 변경하지 않는다. 소속·사진 허가·원본 바이트 검증을 등록과 워커 처리 시 확인한다.

## 배포와 검증

- V17은 기존 작업 테이블의 action_plan 제약을 확장한다. 학습 전용 BASE만 허용하고 이 자료의 APPROVED 상태는 DB에서도 차단한다. 기존 데이터·RLS·권한은 바꾸지 않는다.
- 공통 생성/검수 규칙은 v9를 유지한다. 이번 작업은 이미 승인된 도트와 사람의 평가를 등록하는 경로이며 렌더링 규칙 변경이 아니다.
- 환경변수 추가 없음. V17 적용 후 새 서버를 배포해야 한다.
- 회귀 검사: 원본·권한·크기 오류 차단, 중복/상반된 평가 거절, AI 판정과 인간 피드백 분리, 승인 전 학습 대기, 승인 후에도 생성·공개 차단, 후보 재검증 후 다른 강아지 최초 생성에 전달.
- 모의 응답 검사는 연결을 검증한다. 실제 승인본·실패본 등록, Luna 제안/재검증, 다음 생성 품질 결과는 별도로 기록한다. 노션 외부 쓰기는 이전 별도 승인 대기로 미동기화다.
