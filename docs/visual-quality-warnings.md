# B98 이미지 검수 차단을 경고 기록으로 전환

브랜치: `backend/b-98-visual-warning-mode`. 선행: B97.
노션: 연결 도구가 없어 미동기화. 이 문서는 로컬 작업 기록이며 노션 갱신을 뜻하지 않는다.

## 원인과 선택

2026-10-12 B97 배포 후 검은 강아지 작업 `29445458-c1f6-4d40-8ef1-87ddf212415c`를 실제로 재개했다.
앞·뒤 쉬기와 기본 도트 승인은 보존됐지만, 왼쪽 쉬기의 보완 문구가 1131/1058/1093자로 작성돼
1000자 제한에서 다시 실패했다(`LESSON_PROMPT_INVALID`). 다음 PixelLab 요청 전 실패다.
품질 지적 → 보완 문구 → 의미 재검수의 반복을 중단하고, 사용자가 경고 이미지를 보고 나중에 수정하는
정책으로 바꾸라는 명시적 요청에 따른 변경이다. 이 실패는 이미지 자체의 결함이 확정됐다는 뜻이 아니다.

## 새 작업 동작

- 기본값 `ASSET_VISUAL_WARNINGS_ONLY=true`. 새 일반 작업의 정책에 `visualWarnings=visual-warnings-v1`을 고정한다.
  기존 작업을 일괄 변경하지 않는다. false는 앞으로 생성하는 작업에 이전 차단 정책을 적용한다.
- 기본 도트 4방향과 각 동작의 9프레임을 한 차례 관찰하고, 한국어 경고·방향·프레임 번호를 저장한다.
  관찰 불가/응답 오류/시간 초과는 `UNAVAILABLE` 경고로 남기며 이미지 생성 완료를 막지 않는다.
- 시각 판단에 따른 자동 재생성·편집·반복 재검수와 확정 규칙 학습을 하지 않는다.
  승인된 기존 학습 규칙 전체는 첫 생성 지시문에 계속 입력한다.
  문구의 의미 검증도 경고만 저장하고 재작성하지 않는다. 모든 원본 규칙·초안·검증 응답을 보존한다.
- PNG 해독/크기/투명도, 9프레임 개수, 파일·입력 해시, 원본 사용 허가, 유료 요청 접수 상태는 계속 검증한다.
  전송 불가능한 문구 길이·형식, 원본 분석 실패, 공급자·저장소 오류는 기술 실패이며 경고로 속이지 않는다.
- 이미지가 완성되고 자료 연결이 유효하면 SYSTEM 승인한다. 승인 근거에
  `visualReviewMode=WARN_ONLY`, `approvalBasis=COMPLETE_BOUND_ASSETS_VISUAL_FINDINGS_ADVISORY`를 기록한다.
  이미지 지적이 있는 관찰의 `passed=false`는 유지한다. 품질 검수 통과로 바꿔 쓰지 않는다.
- DB 스키마 변경 없음(V24 유지). 품질 규칙 v43/해시도 유지한다. 이는 새 결함 학습이 아닌 제품 정책 변경이다.
  이미 승인된 이미지와 그림체를 다시 생성하거나 덮어쓰지 않는다.

## 기존 작업의 명시적 전환

`POST /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/visual-warnings`

```json
{
  "requestId": "새 UUID",
  "note": "기존 이미지를 보존하고 품질 지적은 경고로 확인하며 자동 보완은 하지 않습니다.",
  "expectedSeedHashes": {"south":"64자리 SHA", "north":"64자리 SHA", "west":"64자리 SHA", "east":"64자리 SHA"},
  "expectedRulesSha256": "현재 서버와 작업의 동일한 규칙 SHA"
}
```

같은 보호소 STAFF/MANAGER 권한·원본 사용 허가·기본 도트/규칙 해시를 확인한다.
미승인 `SEED_REVIEW`/`REVIEW` 또는 접수 이전 문구 실패 작업만 대상이다.
`APPROVED`, 진행 중, `OUTCOME_UNKNOWN`, 원본/해시 불일치, 이미 접수된 실패는 거부한다.
동일 본문 재전송은 같은 결과를 반환하며, 다른 요청으로 재전환하거나 보완 예산을 초기화하지 않는다.

문구 때문에 실패한 동작은 현재 시도에 유료 접수가 없다는 기록과 마지막 완료 결과를 확인해,
이전 시트를 복원하여 한 번 관찰한다. 과거 비용 영수증·원본·검수·보완 횟수는 남긴다.
기존 통과 동작과 도트 승인도 유지한다. 남은 동작은 정상 생성 1회씩 진행한다.
배포 자체가 원격 보류 작업을 재개하거나 승인하지는 않는다.

## 프론트/담당자 연결

공개 `/v1/dogs/{dogId}/assets`는 완성·승인된 결과만 제공한다. 기존 4방향 프레임 좌표·재생 규약은 같다.
`quality.mode`는 `WARN_ONLY` 또는 `BLOCKING`; `quality.reviewStatus`는 `CLEAR/WARNING/UNAVAILABLE/PENDING`이다.
`quality.passed`와 `quality.warnings[]`를 함께 사용한다. 경고가 있는 팩도 정상 재생할 수 있다.

`GET /v1/shelter-admin/dogs/{dogId}/styled-assets/{jobId}/warnings`는 미완료 작업에도 현재 저장된 이미지를 제공한다.
응답의 `images[]`에는 `label/action/direction`, 원본 `report`, `warnings`, `metadata`,
기본 도트의 방향별 `images` 또는 동작의 `spritesheetUrl`이 있다. URL은 60초 유효하다.
문제 프레임은 0~8이며 시트를 그대로 잘라 보여준다. 경고를 이유로 마지막 프레임을 숨기지 않는다.
일반 사용자에게 담당자 원본 검수 보고서를 노출하지 않는다. 대시보드의 별도 편집 UI는 이번 범위가 아니다.

## 검증 기록

- 배포된 B97 실제 요청: 위 실패를 확인. 임시 STAFF 소속·로그인/계정을 해제했고 기존 B87/B90 승인 기록이 보존됨을 확인했다.
- B98: 단위/임시 PostgreSQL 회귀 검사로 경고 팩 자동 완료, 13회 관찰·13회 최초 생성·보완 0회,
  AI 불가, 기술 손상 차단, 기존 이미지 재사용, 권한·해시·멱등성·기존 승인 자료 보존을 검사한다.
- 모의 AI 검사는 실제 강아지 품질 통과를 뜻하지 않는다. 병합/배포 여부 및 전체 검사 결과는 PR 기록을 따른다.
- B98의 실제 원격 작업 재개·남은 생성·최종 승인·네이티브 앱 재생은 이 버전 배포 후 확인해야 한다.

실제 새 관찰기 확인: 기존 검은 강아지의 앞 쉬기 원본 9프레임과 기준 도트 4개를 Luna에 1회 전달했다.
프레임 1·3·4·5·6·7의 귀/꼬리로 보이는 돌출부 변화에 대해 불확실한 연속성 경고가 기록됐다.
`reviewStatus=WARNING`, `passed=false`, `enforcement=WARN_ONLY`이며 PixelLab 요청 0회, 원격 DB 변경 0회다.
[실제 관찰 보드](../backend/scripts/fixtures/visual-warnings-v1/observation-board.png)와
[원본 관찰 결과](../backend/scripts/fixtures/visual-warnings-v1/observation.json)를 보존한다.
이 결과는 경고 기록의 실제 확인이며 최종 팩 승인이나 앱 재생 완료를 뜻하지 않는다.
