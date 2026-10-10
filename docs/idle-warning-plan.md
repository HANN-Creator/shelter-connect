# B-87 쉬기 정적 대체의 명암 경고 중복 차단

B-86을 배포한 실제 v38 작업의 `idle-west`는 두 관찰 모두 꼬리 움직임을 확인했지만 action 판정은 갈렸다. palette의 PASS/FAIL은 기존 미관 정책에 따라 `PALETTE_UNCERTAIN`, `blocking=false`로 기록됐다. 그런데 정적 대체 계획이 원시 palette FAIL을 다시 읽어 새 후보 생성을 막았다.

`StyledIdleHold.motionOnlyFailure`도 기존 `StyledAestheticPolicy.permitsPaletteWarning`을 사용한다. 올바른 정책과 비차단 경고가 있고 두 관찰이 모두 색상 결함을 확정하지 않은 경우만 적용한다. 원래 불합격 판정을 바꾸지 않고, 이미 승인된 같은 방향 기준 자세로 별도 정적 IDLE을 만들어 새 전체 검수를 받게 한다. 같은 규칙 선택 재개와 일반 워커가 같은 계획을 사용한다.

확정 색상 깜빡임, 경고 근거 누락, 눈·사지·정체성·방향·첫 자세 의심은 계속 차단한다. raw/restored 보고서가 있다면 각 보고서가 조건을 충족해야 한다. 원래 보완 예산 소진 조건과 중복 정적 대체 방지, 기존 통과 이미지와 원본·검수 이력도 유지한다.

실제 응답은 `backend/scripts/fixtures/motion-repair-v38/deployed-warning`에 보존했다. PNG는 기존 v38 회귀 자료와 같은 해시이며 새로 변형하지 않았다. 기존 `motion-repair-entry-and-evidence-v38` 회귀의 후속 조건 검사이므로 규칙 파일·최초 생성 프롬프트·스타일을 바꾸지 않는다. v38 SHA `18305e689493945343289c3ee52547b602d6ae0d0aee32b9e1a7e9f37ab2e8a9`, API·DB V23·환경변수는 그대로다. 수정 후 같은 규칙의 `motion-candidate-repair`로 해당 보고서와 이미지 해시를 확인해 선택 재개할 수 있다. 전체 이미지 재검수나 보완 횟수 초기화가 필요하지 않다.

실제 서버 보고서로 수정 전 분기 실패를 재현하고, 수정 후 통과 및 경고 누락·확정 결함·해부학 의심의 차단을 검사한다. PostgreSQL 검사는 같은 규칙 API, 중복·권한, 기존 통과 시트 보존, PixelLab 추가 요청 없이 새 정적 후보만 검수, 새 검수 실패 시 승인 차단을 확인한다. 공급자 응답을 모의 처리하는 DB 검사와 실제 이미지 품질 검증은 구분한다.

배포 후 자동 승인·저장·앱 재생의 실제 결과는 B-68에 기록한다. B-87은 이 조건 충돌 수정이며 전체 품질 완료를 의미하지 않는다.

[노션 B-87](https://app.notion.com/p/3f55b2d1a55f802f9964ef8886445d95) · `backend/b-87-idle-warning-plan`

검증: 수정 전 실제 보고서의 조건 검사 1건 실패를 재현했다. 수정 후 전체 단위 596 PASS / 24 조건부 SKIP, 관련 PostgreSQL 6 PASS. 모의 DB에서는 새 정적 후보의 PASS 및 FAIL 모두 확인했다. 실제 원격 결과를 통과로 변경한 검사가 아니다. 최종 HEAD의 CI·컨테이너 결과는 PR에 기록한다.
