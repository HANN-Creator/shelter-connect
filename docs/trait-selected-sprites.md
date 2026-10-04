# Luna 특징 기반 선택 생성 (B-42)

사진은 외형 참고, 확인된 관찰/선호 문장은 행동 판단 근거다. Luna(gpt-5.6-luna, 기존 OPENAI_MODEL 설정)가 근거 인용과 태그를 반환하고 서버가 고정 규칙으로 선택한다. 품종·사진으로 성격을 추측하지 않는다.

| 제공 특징 | 기본 IDLE/WALK/SIT 외 추가 | 앱 연출 |
| --- | --- | --- |
| 없음/불명확/미확인 | 없음 | 가만히 쉬기·걷기·앉기 |
| 사람을 좋아함 | TAIL_WAG, SNIFF | 사용자로 걷기 → 꼬리 → 냄새 → 대기 |
| 누워서/엎드려 쉬기 선호 | LIE_DOWN | 눕고 마지막 프레임 유지, 역순으로 일어서기 |
| 냄새 맡기 선호 | SNIFF | 주변 냄새 탐색 |
| 공놀이 선호 | RUN, SNIFF | 공 추적 → 가까이 걷기 → 냄새 → 대기 |
| 산책/달리기 선호 | RUN | 달리며 산책하는 연출 |
| 사람 접근을 피함 | BACK_OFF | 보던 방향을 유지하며 뒤로 이동 |

특징이 여러 개면 필요한 행동의 합집합을 만든다. 추가 동작 최대2개 제한은 없다. 공을 가져온다는 기록이 있을 때만 RETURN을 활성화한다. 산책 중 발견·산책 가능·막연한 활동량은 산책 선호가 아니다. 조용하다는 말만으로 눕기를 만들지 않는다. 달리기 제한 기록은 RUN/공 추적보다 우선하며, 회피 특징은 사람 인사를 끈다.

## 등록 시 한 번 판단하고 저장

1. 같은 강아지의 관찰을 CONFIRMED로 등록.
2. POST `/v1/shelter-admin/dogs/{dogId}/behavior/suggestions`: 기존 clientRequestId/expectedRevision/evidenceObservationIds. 동일 ID 재요청은 Luna를 다시 호출하지 않는다. 결과의 traits 인용문과 generationPlan을 확인한다.
3. POST `.../behavior/confirmation`으로 AI_DRAFT를 확인한다. 관찰 수정/철회나 버전 충돌은 기존 보호 로직으로 차단한다.
4. POST `.../styled-assets`에 photoId/사진외형traits를 전달한다. 확인된 행동을 actionPlan 및 behavior_plan 스냅샷으로 저장한다. 생성 요청 자체는 Luna를 재호출하지 않는다.
5. 4방향 기준 이미지를 검토한 뒤 선택된 동작만 PixMiniMax로 생성하고, 최종 검토 후 공개한다.
6. 방문자는 GET `/v1/dogs/{dogId}/assets`만 사용한다. 조회/화면 입장/새로고침으로 AI나 PixelLab을 호출하지 않는다.

기본3개면 기준이미지1회 + 3×4 = **13회 생성 POST**, 사람 선호5개면 **21회**, 전체8개면 **33회**다. 요청 수는 공급자의 청구 Generations와 다르다. 기존 PixelLab 일일10회 제한은 제거된 상태다. 별도의 Luna 행동 초안 한도 `BEHAVIOR_AI_DAILY_LIMIT` 기본20/UTC일은 기존 설정을 유지한다.

외형·seed·확인된 행동 버전을 바꾸고 새 생성 요청을 보내면 새 팩 비용이 발생할 수 있다. 기존 팩은 자동으로 재생성하지 않는다. 동일 입력·버전 재요청은 같은 작업이다. 선택 목록은 생성 도중 변경되지 않는다. 이전 전체33단계 작업은 그대로 재개·조회할 수 있다. 이전 버전에서 보낸 동일 사진외형 입력의 재전송도 기존 팩을 반환해 업그레이드 직후 재결제를 방지한다.

## 프론트 연결 계약

- `availableActions` 및 방향별 실제 clip을 기준으로 UI/랜덤 행동을 선택한다. 존재하지 않는 시트 URL을 조합하지 않는다.
- 공개 manifest의 `generationPlan`은 **생성 당시** 선택 메타데이터다. 개인정보/관찰 인용/관찰 ID는 포함하지 않는다.
- `behavior`는 **현재** 유효한 설정을 실제 팩과 교차 검사한 결과다. 여기의 settings/weight/speed와 interactions를 사용한다. 근거 철회 시 DEFAULT로 돌아가고, 작은 기존 팩에 없는 동작 비중은0, 필요한 시트가 빠진 상호작용은 꺼진다.
- `behavior.interactions.PERSON_GREETING.enabled`이면 triggerDistanceTiles 내 사용자로 WALK. arrivalDistanceTiles에서 멈춘 뒤 TAIL_WAG(wagDurationMs), SNIFF(sniffDurationMs), IDLE. 타겟 이탈/소실 또는 maxDurationMs 초과 시 IDLE. 종료 후 cooldownMs 적용. BACK_OFF가 있는 조심스러운 설정에서는 먼저 접근하지 않는다.
- `BALL_CHASE.enabled`이면 공을 표시하고 CHASE(RUN/WALK) → APPROACH(WALK) → INSPECT(SNIFF/IDLE) → RETURN(명시적 returnEnabled만) → FINISH(IDLE). 공·사용자 좌표, 경로, 충돌, 타일→화면 좌표, 단계 시간은 프론트가 구현한다. 공을 물고 있는 별도 9번째 이미지 동작을 생성하지 않는다.
- 비활성 레시피는 실행하지 않는다. preferredActions는 실제 availableActions 안에서만 선택하고 없으면 IDLE로 돌아간다. 한 번에 하나의 상호작용만 실행한다.
- 사진용 traits와 행동용 traits는 별개다. 기존 스타일/32px 픽셀/4방향 기준 프레임 규칙은 바꾸지 않았다.

## 검증

자동 검사: 기본3개 생성/완료/재조회, 다중 특징29단계, 기존33단계 호환, 같은 요청의 AI·PixelLab 중복 없음, 초안 확인 전 기본값, 근거 철회와 시트 부족 시 상호작용 차단, 누락 방향 승인 거부. 실제 API 분류 결과·배포 SHA는 작업 카드에 기록한다. 이미지 품질과 RN의 실제 이동/공 표시 검증은 프론트 연결 시 별도다.
