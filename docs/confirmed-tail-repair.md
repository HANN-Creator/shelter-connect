# B-79 확정 꼬리 결함과 외형 불확실의 보완 충돌

[노션 카드](https://app.notion.com/p/3f55b2d1a55f807ba8a5daa70a8c574b) · `backend/b-79-confirmed-tail-repair`

v33 배포 작업에서 정면 IDLE의 실제 1–6번 프레임에 원본에 없는 머리 위 부속물이 생겼다. 두 독립 관찰은 action·idleStillness·tail을 모두 FAIL로 확인했지만, 동일 프레임의 identity는 UNCERTAIN/PASS였다. 기존 보완 조건은 identity의 FAIL/PASS 조합만 처리해 확정된 꼬리 결함의 편집도 차단했다.

`confirmedTailRepair`는 이제 identity의 PASS/UNCERTAIN 조합도 처리한다. 두 tail 관찰이 모두 FAIL이고, 불확실한 identity의 구체적 프레임이 두 tail 관찰의 공통 결함 프레임 안에 있을 때만 기존 보완을 허용한다. 다른 불확실 속성, 프레임 근거 누락·불일치·잘못된 형식, 꼬리 관찰 불일치는 계속 차단한다. 양쪽 identity가 모두 불확실한 경우도 허용하지 않는다.

원래 보고서는 여전히 UNCERTAIN이다. 이를 PASS로 바꾸거나 부정 학습 자료로 쓰지 않는다. 기존 보완 횟수·한도, 원본 9프레임·영수증·검수 기록을 유지하고, 새 후보는 전체 독립 검수를 통과해야 SYSTEM 승인된다. 새 생성과 명시적으로 재개한 기존 작업이 같은 보완 조건을 사용한다. 승인 기준을 낮추거나 예산을 초기화하는 변경은 없다.

공통 규칙의 기존 IDLE·frontOcclusion 문구는 첫 생성과 보완에서 머리 윤곽 고정 및 숨은 꼬리 유지에 사용한다. 이번 사례를 회귀 목록에 추가한 v34 SHA는 `603dbd94b26f7a5b4c443a5e7b9f2e72a53cea7f24b0af6eca297148dbc60c7e`이다. API 경로·DB V23·환경변수는 그대로다. 이미 실행 중인 v33 작업은 자동으로 새 규칙에 바뀌지 않는다.

## 검증 자료와 범위

- `backend/scripts/fixtures/confirmed-tail-v33/`: 실제 배포 원본 시트, 4방향 기준 PNG, 검수 원문과 해시. 9프레임 전체를 보존했다.
- 결정적 회귀: 실제 이미지와 두 실제 관찰을 생산 검수 코드로 재생한다. 원래 UNCERTAIN·승인 차단을 보존하면서 확정 결함의 보완만 허용하는지 검사한다. 외부 모델을 새로 호출한 결과가 아니다.
- PostgreSQL 통합: 실제 worker의 자동 편집·이력·횟수 보존, 불확실 자료 학습 제외, 새 후보 PASS 시 승인 및 UNCERTAIN 시 보류를 검사한다. provider 이미지와 새 판정은 모의 응답이므로 새 이미지 품질 통과로 해석하지 않는다.
- v33 실제 배포에서는 BASE가 SYSTEM 승인됐고, 뒤쪽 IDLE은 PixelLab 편집 1회 후 전체 QA를 통과했다. 정면 IDLE에는 이번 수정이 필요하다. v34 배포 후 같은 작업에서 보완 결과와 최종 승인·앱 재생을 별도로 확인한다.
- 별도 관찰: 같은 BASE 이미지가 첫 v33 재검수에서 EYE_READABILITY, 보완 재개 관찰에서 PASS로 달라졌다. 실제 이미지 편집은 없었다. 이 모델 판단의 일관성 문제를 이번 꼬리 보완 수정으로 해결했다고 주장하지 않는다.
