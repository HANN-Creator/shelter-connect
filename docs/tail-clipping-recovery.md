# B-46 · 실제 꼬리 잘림 복구

직전 자동 보완은 가장자리에 가까운 같은 방향 기준 프레임에서 애니메이션을 다시 생성했다. 기존 왼쪽 시트는 2~7번 프레임, 오른쪽은 1·2·3·5·6·7번 프레임에서 꼬리가 경계에 닿았으며 재생성 뒤에도 그대로 남았다. 이번에는 실제 9프레임 전체를 PixelLab `edit-animation-v2`에 보내 내려온 꼬리 끝을 안쪽으로 그리도록 편집하고, 검토한 꼬리 영역만 승인 원본에 합쳤다.

## 실제 결과

| 방향 | 채택한 결과 | 검증 |
| --- | --- | --- |
| 정면 | 정면 편집의 낮은 꼬리 + 원본 몸 | 상부 돌출 없음, 9프레임 가장자리 접촉·분리 픽셀 없음 |
| 왼쪽 | 왼쪽 편집의 꼬리 + 원본 몸 | 9프레임 가장자리 접촉·분리 픽셀 없음 |
| 오른쪽 | 검증된 왼쪽 **꼬리 영역만** 반전 + 오른쪽 원본 몸 | 원본 얼굴·몸·무늬 보존, 9프레임 가장자리 접촉·분리 픽셀 없음 |
| 뒷면 | 기존 정상 시트 유지 | 변경 없이 9프레임 재검사 통과 |

오른쪽 편집 응답은 **불합격**이다. 9프레임 모두 경계 접촉, 1·5·8번에 분리 픽셀이 있었고 일부 꼬리도 높아졌다. 이 원본을 마스킹해 통과시킨 것이 아니다. 오른쪽 편집 전체를 제외하고 별도로 통과한 왼쪽 움직임을 사용했다. 사진의 비대칭 몸 무늬는 반전하지 않았다.

PixelLab 실제 사용량은 편집 3회 × 20 = **60 Generations**였다. 실패한 오른쪽 결과도 사용량에 포함한다. 호출별 usage와 원본·마스크·검토 해시를 로컬 결과 폴더에 보존했다. 이 단가를 PixMiniMax 동작 생성 단가와 혼동하지 않는다.

결과는 native 32×32, 방향당 9프레임이다. 전체 36프레임의 가장자리에 최소 1픽셀 여백이 있고 픽셀이 몸에서 떨어져 있지 않다. 정면·양옆은 마스크 밖의 승인 도트를 바이트 단위로 유지한다. 꼬리 색은 원본 팔레트에서 가장 가까운 색으로 선택하며 위치·크기·실루엣을 흐리거나 재표본화하지 않는다. 0번 기준 프레임은 보존하고 나머지 8프레임을 버리지 않는다. 별도의 자동 해부학 검증 완료를 뜻하지 않으며 실제 접촉표와 브라우저 재생도 검토했다.

## 백엔드 도구

`backend/scripts/repair_styled_tail.py`는 명시 실행형 복구 도구다. 기존 `Client`의 요청 해시·잠금·job ID·불명확한 접수 재전송 금지를 그대로 사용한다. `edit`는 결함이 있는 원본을 편집 서비스로 보내 원본 응답을 보존하고, `compose`는 검토된 입력으로 꼬리만 복원한다. 두 단계 모두 DB·Storage·공개 상태를 변경하지 않는다.

```sh
cd backend
python3 scripts/repair_styled_tail.py edit \
  --sheet /absolute/bad-tail-west.png --direction west \
  --output /absolute/review/edit-west --env-file .env.pixellab --allow-paid-calls
# 실제 raw-edit.png 전체를 검토한 뒤, 해당 개체의 꼬리 마스크와 검토 JSON 작성
python3 scripts/repair_styled_tail.py compose \
  --sheet /absolute/review/edit-west/raw-edit.png \
  --seed /absolute/approved-west.png --source-seed /absolute/approved-west.png \
  --mask /absolute/west-tail-mask.png --review /absolute/west-review.json \
  --output /absolute/review/composed-west
```

검토 JSON은 `approved: true`, 충분한 `note`, `tailCarriage: LOW`, `direction`, `sourceDirection`, `seedPixelSha256`, `sourceSeedPixelSha256`, `editPixelSha256`, `maskPixelSha256`를 포함한다. 해시는 각각 native RGBA 원본·source 원본·9프레임 연결 RGBA·binary L 마스크 픽셀에 대한 SHA-256이다. 마스크나 원본·편집 프레임이 달라지면 다시 검토해야 한다. 왼쪽↔오른쪽만 반전을 허용한다. 이 개체의 마스크 좌표를 다른 개체의 꼬리 분할 규칙으로 사용하면 안 된다.

합성 전에 **편집 원본 9프레임 전체**의 가장자리·분리 픽셀·정면 LOW 상부 돌출을 검사한다. 잘린 0번을 승인 이미지로 바꾸거나 마지막 프레임을 지워 통과시킬 수 없다. 합성 후 같은 검사, 실제 꼬리 실루엣 변화, 원본 팔레트·몸 보존을 검사한다. 정지된 꼬리의 색 깜빡임만으로는 통과하지 않는다. 결과 상태는 `LOCAL_REVIEW_CANDIDATE`, `published=false`, `visualReviewRequired=true`다.

공통 품질 규칙 v3에 편집 지시와 실제 실패 회귀를 저장했다. `tail-repair-alpha.json`은 실제 실패·편집·수정본의 알파 좌표를 그대로 재현한다. 사진·개체 ID·계정 정보는 포함하지 않으며 색상·해부학 품질을 검증하는 자료로 과장하지 않는다. Python 검사 68개와 관련 Java 검사 11개, 서버 패키지 빌드를 통과했고 실제 로컬 결과도 이 백엔드 도구로 다시 합성하여 화면에 표시한 PNG와 픽셀이 일치함을 확인했다.

## 적용 범위와 남은 상태

이번 미리보기는 **TAIL_WAG의 로컬 수정본**이다. 다른 행동의 남은 품질 문제, 기존 서버 작업의 `FAILED` 상태, 검토/공개 상태는 바꾸지 않았다. 기존 자동 워커의 재생성 경로를 이 수동 검토 복구 경로로 자동 전환하지 않았다. 따라서 일반 개체 전체에 대해 자동 보완이 해결되었다고 보고하지 않는다.

품질 규칙 해시가 바뀌므로 배포 시 기존 작업은 명시적 재검수 대상이다. 이미 소진된 보완 횟수나 운영자 복구 권한을 우회하지 않는다. 실제 서버 결과로 채택하려면 별도 승인된 복구·저장·검토 절차를 거쳐야 한다. 브랜치 `backend/b-46-tail-clipping-repair`. 노션 외부 기록은 앞선 승인 검토 거절과 별도 질문 대기 상태로 미동기화이다.
