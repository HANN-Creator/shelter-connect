# B-80 저장된 이미지의 검수 시간 초과 복구

B-79 이후 같은 강아지를 다시 만들지 않고 v33 실패 지점에서 이어가기 위한 변경이다. 실제 실패 메타데이터는 `backend/scripts/fixtures/quality-timeout-v33/failure.json`에 보존했다. BASE SYSTEM 승인, 정면·뒤 IDLE 완료 후 `idle-west`에서 생성 응답은 DB에 저장됐지만 이미지 검수의 `QUALITY_AI_TIMEOUT` 때문에 FAILED가 됐다. 이때 `result`는 아직 없고 `provider_result`와 STARTED 표시가 남는다.

## 자동 처리

새 작업의 `qualityTimeoutVersion=saved-quality-timeout-v1`에서 **검수가 시작된 PERSISTING/CHECKING 단계의 QUALITY_AI_TIMEOUT만** 다시 시도한다. 작업 소유 lease와 사진·사용 권한을 다시 검사하고 저장된 provider 응답/완성 이미지를 사용한다. 원래 시도 외에 동작별 최대 두 번, 15초·60초 간격이다. BASE에도 적용한다. 원본·외부 영수증·request SHA·보완 횟수는 그대로이며 PixelLab 제출은 없다. 시도 횟수·시간·자료 해시·오류·지연은 attempt_history에 저장되어 재시작이나 재배포로 한도가 초기화되지 않는다.

불합격·UNCERTAIN 판정, 외부 이미지 요청 결과 불확실, 인증·설정 오류, 다른 응답 오류, 저장 자료 누락은 이 경로의 대상이 아니다. 권한이 취소되면 취소 상태를 유지한다. 마지막 시간 초과는 FAILED로 남고 자료를 보존한다. 시간 초과를 품질 결함이나 학습 예제로 기록하지 않는다.

## 이미 실패한 작업

보호소 쓰기 권한을 가진 담당자가 아래 경로를 사용한다. OPERATOR 생성이나 DB 상태 수정은 필요 없다.

- `GET /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-timeout-checkpoint`: 한 동작만 시간 초과로 실패하고 기존 SYSTEM BASE 승인이 있는 작업의 해시·진행 상태 조회. raw 이미지, 서명 URL, 토큰은 응답에 없다.
- `POST /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-timeout-resume`: 조회한 label, checkpointSha256, seedHashes, rulesSha256와 requestId, note, expectedSheetHashes를 보낸다. 명세의 필드명은 expectedCheckpointSha256, expectedSeedHashes, expectedRulesSha256이다.

같은 규칙이면 실패한 단계만 재개하며 expectedSheetHashes는 빈 객체다. 규칙이 바뀌면 완료된 결과를 새 규칙으로 재검수하고, 선택한 완료·불합격 시트에만 원래 남은 보완 횟수를 허용한다. 기존 보고서와 BASE 승인 이력은 보존하며 새 검수 없이는 승인하지 않는다. 생성 중이었던 다른 PENDING 동작은 원래 계획으로 이어진다.

명시 복구도 자동 재시도와 같은 총 두 번의 한도를 소비하고, 동작당 한 번만 새 복구 요청을 받는다. 동일 requestId와 본문은 재전송해도 추가 실행하지 않는다. 해시 불일치·소진·사진 허가 취소·다른 실패·중복 새 요청은 409, 소속 없는 사용자는 403이다. 데이터 확인과 전이는 DB 잠금 하에서 처리한다. 실제 이미지 생성과 검수 호출은 DB 트랜잭션 밖에서 실행한다.

## 검증과 범위

저장된 생성 응답 단계, 완성 시트 검수 단계, BASE 시간 초과, 재시도 지연·영속 한도, 중복 요청, 새 규칙의 부분 팩 이어가기, 권한·자료·판정 경계를 DB 검사로 확인한다. 이 검사의 제공자/AI 결과는 모의 값이며 실제 새 이미지의 품질 통과를 뜻하지 않는다.

DB 스키마 V23과 품질 규칙 v34 유지. 새 환경변수 없음. 이 문서를 작성한 시점에 실제 작업은 FAILED이며 임시 로그인은 해제했다. 배포 후 동일 작업을 이어서 생성→보완→최종 자동 승인→앱 재생을 검증해야 한다. BASE 눈 판정의 관찰 간 변동은 이 전송 복구 변경으로 해결한 것이 아니다.
