# 쉬기 동작의 확정 결함 보완과 정지 자세 대체

B-75는 실제 B-74 패키지 실행에서 발견한 정면 IDLE의 머리 위 부속물과 IDLE 유료 편집 소진을 다룬다. 그림체와 승인 BASE는 바꾸지 않는다. v27 공통 규칙을 최초 생성·보완·검수에 함께 사용한다.

## 관찰 충돌

정면 IDLE에서만 두 독립 관찰이 모두 꼬리 움직임·action/idleStillness/loop 결함에 동의하고, 다른 항목은 PASS이며, 코드가 실제 중앙 머리 영역의 신규 픽셀과 고정 하체를 확인할 때 tail의 FAIL/UNCERTAIN을 확정 결함으로 묶는다. 이는 통과 판정이 아니며 유료 보완을 허용하는 근거다. 사진 속 꼬리 높이가 UNKNOWN인 사실만으로 새 머리 부속물을 허용하지 않는다. 정상 귀/호흡, 옆·뒤 방향, 다른 항목의 불확실성은 기존대로 보류한다.

원본 9프레임과 두 실제 관찰은 `backend/scripts/fixtures/idle-hold-v26/`에 보존한다. 회귀 검사에서 실제 판정 재생과 모의 상태 전이 검증을 구분한다.

뒤 WALK 실제 검수에서는 두 관찰이 같은 꼬리/머리 부속물 결함에 동의했지만 한쪽만 같은 프레임을 identity 실패로도 분류했다. 양쪽의 확정 tail 프레임 교집합 안에 identity 실패 프레임이 모두 포함되고 다른 불확실성이 없으면, 그 확정 꼬리 결함만 원래 예산으로 보완할 수 있다. 기존 UNCERTAIN 판정과 학습 보류는 그대로이며 새 결과의 전체 검수 통과가 필요하다. disjoint identity 실패·tail 자체 불확실·다른 항목 불확실·두 번째 관찰 누락은 차단한다. 실제 `walk-north-v26-review.json`과 원본 9프레임으로 회귀 검사한다.

## 유료 보완 소진 후 IDLE

기존 최대 보완 횟수를 소진한 확정 실패 IDLE은 승인된 같은 방향 BASE를 손실 없이 40px로 패딩한 정지 자세 9프레임을 별도로 만든다. `approved-seed-idle-hold-v1` 파생 정보·출처 해시·이전 실패 시트 해시를 기록하며 보완 횟수나 영수증을 초기화하지 않는다. PixelLab 추가 호출은 없다. 이전 전체 원본/편집/검수는 이력과 기존 저장 경로에 남는다.

대체 결과는 별도 `/sheets/idle-hold/` 경로를 쓰고 새 Luna·전체 픽셀 검수를 거친다. 검수 실패·불확실이면 공개하지 않고 다시 대체하지 않는다. 이전 원본/보정의 모든 독립 관찰이 action·idleStillness 실패에 동의하고 불확실 항목이 loop뿐이면, 그 판정을 변경하지 않고 새 정지 결과를 만들 수 있다. 이는 실패 시트의 승인이나 불확실한 항목의 학습이 아니다. 외형·방향·꼬리·action 등 다른 불확실성은 계속 차단한다. WALK/SIT에 정지 대체를 쓰지 않는다. API의 각 클립 `motionKind`는 `STATIC_IDLE` 또는 `ANIMATED`이며 기존 9프레임 재생 계약은 유지한다. 정지 대체를 PixelLab이 만든 움직이는 애니메이션으로 표시하지 않는다.

## 이미 끝난 실패 팩 재개

`POST /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/motion-repair-resume`

- 요청: `requestId`, 20자 이상 `note`, 4방향 `expectedSeedHashes`, 실패 label→SHA의 `expectedSheetHashes`, 작업에 고정된 이전 `expectedRulesSha256`.
- 같은 보호소 쓰기 권한·사진 사용 허가·REVIEW 완성 팩·과거 BASE 승인 근거·현재 이미지 해시·규칙 변경을 확인한다.
- 전체 이미지를 새 규칙으로 재검수하되, 선택된 실패 동작만 원래 남은 유료 예산을 사용한다. 소진된 IDLE은 새 검수 후 정지 대체를 사용할 수 있다. BASE 재생성은 허용하지 않는다.
- 기존 `quality-recheck`는 이미지 재생성 없는 검사다. 새 요청은 별도이며 권한과 원본을 유지한다.
- 작업·새 규칙 버전별 1회다. 이전 재개 요청은 `motionRepairResumeHistory`에 보존하며 모든 이전 요청의 동일 본문은 완료 뒤에도 재사용한다. 같은 규칙에 다른 요청, 과거 요청 본문 변경, 낡은 해시, 미완성 팩, 통과 동작, 소진된 WALK/SIT는 409다.
- DB V23 유지, 추가 환경변수 없음. 배포와 실제 검증 결과는 아래에 구분하여 기록한다.

## 검증 기록

- Java 단위 488개 중 474개 통과, 별도 실호출 전용 14개 생략.
- PostgreSQL 통합 477개 중 475개 통과, 기존 2개 생략. 새 권한·낡은 해시·중복·미완성·소진 WALK 차단, 기존 예산·원본 보존, IDLE 대체의 새 검수 통과/실패 차단을 포함한다.
- Python 61개 통과. 모든 행동/방향/꼬리/보완 조합의 프롬프트 길이와 공통 규칙 연결 포함.
- v26 규칙 SHA: `cdd63d12b0eb39ff39518b7188f99196cf8e5795a33810cc70a95b42453a1856`.
- 실제 v25 격리 작업은 12개 생성 완료, 10개 통과·앞/뒤 IDLE 2개 보류였다. v26 동일 작업 재개로 앞 IDLE은 실제 PixelLab 편집 1회 후 통과했다. 뒤 IDLE은 원본의 loop 불확실성이 확정된 IDLE 실패까지 막아 v27의 별도 정지 대체 경계 회귀 사례가 되었다. v27의 실제 실행·최종 승인·저장/조회 결과와 배포 여부는 [PR #88 검증 기록](https://github.com/HANN-Creator/shelter-connect/pull/88) 및 [B-75 작업 기록](https://app.notion.com/p/3f45b2d1a55f80bdb396fd29378ecdc3)에 실행 근거와 함께 기록한다. 단위/통합 회귀 통과만으로 실제 모델 품질이나 운영 배포 완료를 의미하지 않는다.
- v27 규칙 SHA: `2a0f031e1b982d9df39940dca03124da771d2e39cbb197e749b34204ec1b46d3`.

## 응답 형식 오류 복구

실제 v27 팩은 BASE·IDLE 4방향·WALK 4방향을 통과한 뒤 sit-south에서 `QUALITY_MOTION_RESPONSE_INVALID`로 중단됐다. 이전 구현은 잘못된 응답 원문을 저장하지 않았으므로 어떤 필드였는지는 확인할 수 없다.

`named-motion-response-v1`은 필수 속성을 이름별 객체로 요청해 중복/누락을 막고, FAIL은 최소 한 개의 근거 프레임을 요구한다. IDLE이 아닌 동작의 idleStillness는 적용 대상이 아니므로 PASS만 허용한다. JSON 스키마 제약은 [OpenAI 공식 Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs)를 따른다. 기존 저장 보고서는 배열 형태를 유지한다. 품질 규칙·기준 버전·해시는 바꾸지 않는다.

형식 검증에 실패한 관찰만 같은 입력으로 한 번 재요청한다. 정상 FAIL/UNCERTAIN은 형식 재시도 대상이 아니며, 정상 첫 관찰을 버리고 재투표하지 않는다. 잘못된 응답은 최종 보고서나 실패 진단에 보존한다. 공급자 시간초과/거절/인증 오류는 이 재시도의 대상이 아니다.

`POST /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/quality-response-resume`은 같은 보호소의 쓰기 권한·사진 사용 권한·기존 SYSTEM BASE 승인·이미지/규칙 해시를 확인한다. 정확히 한 개의 저장 동작이 응답 형식 오류로 실패했을 때만 해당 검수 단계로 복구한다. 동작/응답 프로토콜 버전당 1회이며 요청 ID와 본문은 재전송 가능하다. 기존 검수/이미지/횟수/영수증/예산은 그대로다. 실제 데이터의 상태나 판정은 SQL로 재설정하지 않는다.
