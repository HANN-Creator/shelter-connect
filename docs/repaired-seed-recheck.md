# B-71 · 보완 이력이 있는 기본 도트 재검수

배포된 v18에서 1회 보완 후 보류된 BASE를 `quality-recheck`로 다시 검사하면, worker가 `CHECKING` 분기 전에 이전 편집 계획을 읽었다. 재검수는 현재 결과를 같은 `repairCount`로 이력에 보존하지만, 편집 경로는 `repairCount - 1`을 요구한다. 실제 원본은 바뀌지 않았는데 `RECOVERY_INPUT_CHANGED`로 종료되었고 Luna 검수에도 도달하지 못했다.

작업: https://app.notion.com/p/3f45b2d1a55f80cda2aaf14a33b55f56

## 변경

- `CHECKING`은 현재 저장된 BASE 네 방향의 PNG·해시·경로를 검증하고 새 규칙으로 검수한다. 이전 유료 편집 계획을 읽지 않는다. 실제 편집 요청과 공급자 결과 처리의 이력·해시 검사는 유지한다.
- 이미 실패한 해당 작업은 기존 `POST /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-recheck`로 다음 규칙 배포 후 재검수할 수 있다. `FAILED / RECOVERY_INPUT_CHANGED`, 선택 보완 정책, 검수 전 실패한 BASE, 동일 결과·동일 보완 횟수가 담긴 마지막 `qualityRecheck` 이력을 모두 확인한다. 임의 실패·다른 결과·누락 이력은 거부한다.
- 기존처럼 `expectedSeedHashes`, 현재 작업에 고정된 `expectedRulesSha256`, 20자 이상 `note`가 필요하다. 권한과 사진 사용 허가를 다시 검사한다. 규칙이 동일하면 재시작하지 않는다. 수락 후 같은 요청은 같은 작업을 반환한다.
- 재검수는 유료 이미지 편집을 시작하거나 보완 횟수를 초기화하지 않는다. 실패한 과거 검수/현재 결과를 이력에 보존한다. 저장 픽셀이 달라졌으면 AI 전송 전에 차단한다. 불합격은 계속 보류되며, 해시가 일치하는 신선한 검수 통과만 기존 SYSTEM 승인 경로로 이어진다.

DB V23, 환경변수, 응답 구조는 변경하지 않는다. 원격 상태를 SQL로 초기화하거나 OPERATOR를 새로 만들지 않는다. 새 그림체·검수 완화는 없다.

## 회귀와 실제 확인 범위

실제 실패의 작업 ID, 이미지 해시, 보완 횟수와 마지막 재검수 이력 관계를 `backend/scripts/fixtures/repaired-seed-recheck-v19/evidence.json`에 보존했다. PNG 원본은 기존 `tail-refinement-v18` fixture와 동일하다. 고장난 worker에서 재현 테스트가 먼저 실패함을 확인했다.

PostgreSQL 검사는 1회/3회 보완 후 재검수, 유료 요청·PNG·횟수·이력 보존, 승인 차단, 새 규칙 이후 제한된 재개, 타인·오래된 입력·누락/변경 이력 거부, 새 검수 통과 후 12동작 SYSTEM 승인을 다룬다. 공급자 모의 검사는 실제 이미지 품질 통과를 뜻하지 않는다. 실제 서버 재검증과 12동작의 시각 결과는 B-68에 별도 기록한다.

공유 규칙 revision: `sprite-regressions-2026-10-09-v19`, SHA256: `8f893aa807318d61a8fb6de658d70fcfe085994ef2c9919cf7f41568195512e5`. 추가 내용은 재검수 제어 흐름의 회귀 항목이다. 최초 생성·보완의 기존 예방 프롬프트, 스타일 참조, 품질 임계값은 v18과 동일하다. 실행 중인 v18 생성이 끝나기 전에 규칙 버전을 바꾸어 배포하지 않는다.
