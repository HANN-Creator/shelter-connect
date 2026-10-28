# 둘만의 정원·로딩 화면 연결 (B-110)

승인된 첫 대화 화면의 정원과 강아지를 제거한 로딩 화면을 서버에서 조회한다. DB에는 버전·배치·프레임·파일 해시를, 서버 배포 파일에는 PNG·JSON을 저장한다. 특정 강아지, 이름, 대화 문장은 배경에 포함하지 않는다.

## 조회

API 원점: `https://api.shelterconnect.win`

| 용도 | GET 경로 |
| --- | --- |
| 최신 정원 | `/v1/meeting-scenes/dog-meeting-courtyard` |
| 최신 로딩 | `/v1/meeting-scenes/dog-meeting-loading` |
| 정원 버전 고정 | `/v1/meeting-scenes/dog-meeting-courtyard/versions/2.0.0` |
| 로딩 버전 고정 | `/v1/meeting-scenes/dog-meeting-loading/versions/2.0.0` |
| 선택된 강아지 | `/v1/dogs/{dogId}` |
| 선택된 강아지 스프라이트 | `/v1/dogs/{dogId}/assets` |

메타데이터 응답은 `{data:{id,version,title,width,height,assetBaseUrl,manifestUrl,manifestSha256,sourceSha256}}`이다. `manifestUrl`을 API 원점에 붙여 읽고, manifest의 상대 파일 경로는 `assetBaseUrl` 기준으로 해석한다. 최신 조회는 숫자 버전 순이며 `no-store`다. 한 화면에서는 같은 버전의 파일만 조합한다.

정적 파일 주소는 `/v1/map-assets/2.0.0/{sceneId}/{file}`이며 공개·버전 고정·1년 immutable 캐시다. 60초 뒤 만료되지 않는다. 디자인을 수정하면 새 버전으로 등록하고 이전 주소의 바이트는 바꾸지 않는다. 과거 정원 `1.0.0`과 보호소 맵 3종은 계속 제공한다.

## 선택된 강아지와 로딩

1. 선택한 `dogId`를 화면 이동 상태로 유지한다. 로딩·정원·프로필·대화 생성 모두 같은 ID를 사용한다.
2. 로딩 manifest의 `layout`(`animation.json`)을 읽고 `background → gardenBack → selectedDog → gardenFront → paws` 순서로 합성한다. 120프레임, 20fps, 6초 루프이며 준비가 끝나면 루프 종료를 기다리지 않고 대화 화면으로 이동한다.
3. 강아지 에셋의 `mapDirections.RIGHT.RUN`이 있으면 사용하고, 없으면 `RIGHT.WALK`를 사용한다. 실제 응답에 존재하는 동작만 고른다. 둘 다 없으면 정원만 표시하며 다른 강아지로 대체하지 않는다.
4. 강아지의 프레임 크기, `frames`, `durationMs`, `anchorPixels`를 따른다. 예시의 32px·40px·9프레임을 고정하지 않는다. `timeline[].dogAnchor`는 정원 원본 240×188 기준이며 화면에서는 3배 배율이다. `dog-clip-mask.png`로 정원 바깥으로 달리는 강아지를 잘라낸다. 환경과 강아지 프레임 시간은 독립적이다.
5. 정원은 `scene.json` 순서대로 156개 배경 스프라이트를 그린다. atlas와 `spritesheet.json` 또는 `catalog.json`의 개별 PNG를 쓸 수 있다. 사용자 요청으로 줄인 강아지 크기·발 위치는 manifest의 `selectedDog.placement`를 따른다. 종횡비와 API의 발 기준점을 보존하고 앞 방향 `mapDirections.DOWN.IDLE`을 우선한다. 없으면 같은 강아지의 앞 기본 도트 `baseUrl`과 `baseFrameSize/baseAnchorPixels`를 사용한다.

강아지 PNG의 서명 URL은 기존 접근 제어에 따라 약 60초 유효하다. 파일 자체가 삭제되는 것은 아니다. 화면 진입 시 manifest와 이미지 바이트를 미리 로드한다. 다운로드 완료된 이미지는 애니메이션 중 URL을 다시 요청하지 않는다. 아직 다운로드하지 않은 URL이 만료됐거나 재입장·앱 복귀로 다운로드가 필요하면 `/assets`를 다시 읽는다. URL을 영구 프로필 주소처럼 DB에 보관하지 않는다. 공개 정원 파일과 비공개 동물 사진·생성 자료의 접근 규칙을 섞지 않는다.

## 첫 대화와 저장

`POST /v1/dogs/{dogId}/chat-sessions`로 해당 강아지 대화방을 열고 `GET /v1/chat-sessions/{sessionId}/messages`로 기존 메시지를 읽는다. 사용자 메시지 저장 후 해당 메시지의 `/reply`를 요청하는 기존 흐름을 따른다. 시안의 ‘두부’·인사말을 모든 강아지에게 고정하지 않는다. 오류 시 다른 강아지 대화방으로 넘어가거나 동일 메시지를 무한 재전송하지 않는다. 요청 형식·중복 방지는 [대화 저장](chat-storage-api.md), [AI 답변](grounded-chat-api.md)을 따른다.

## 지금까지 만든 화면과 서버 대응

| 화면 | 기존 서버 연결 | 판단 |
| --- | --- | --- |
| 로그인·내 정보·설정 | Auth, `/v1/me`, `/me/profile`, `/me/preferences`, `/me/consents`, `/registration-policy` | 기존 기능 사용. 홈 프로필 사진 진입은 프론트 라우팅 |
| 가까운 보호소·목록·상세 | `/v1/shelter-discovery`, `/shelters`, `/shelters/{id}`, `/shelters/{id}/dogs` | 위치를 전달해 거리 정렬. nextCursor로 계속 조회 |
| 보호소 운동장 3종 | `/v1/shelters/{id}/maps` | 기존 맵과 보호소 자동 연결 유지 |
| 강아지 프로필·행동 | `/v1/dogs/{id}`, `/dogs/{id}/behavior`, `/dogs/{id}/assets`, 조건부 `/photos` | 이름·보호소·확인된 특징·실제 에셋 사용 |
| 저장한 친구 | `/v1/me/saved-dogs` 및 `/{dogId}` GET/PUT/DELETE | 기존 개인별 저장 사용 |
| 강아지 대화 목록 | `/v1/me/dog-conversations` | 기존 검색·저장 친구 필터 사용 |
| 이웃 문의·안 읽음 | `/v1/inquiry-rooms`, `/{id}/messages`, `/{id}/read` | 기존 검색·unreadOnly·읽음 사용 |
| 커뮤니티·작성·댓글·제보 | `/v1/community/posts`와 하위 API | 기존 권한·임시 사진·게시 상태 사용 |
| 정원 첫 대화 | 새 정원 v2 + 기존 선택 dogId·대화 API | 이번에 에셋·최신 조회 등록 |
| 정원 이동 로딩 | 새 로딩 v2 + 기존 선택 dogId의 에셋 | 이번에 전후경·타임라인 등록 |

프로필 시안의 건강·활동·친화도 5단계 막대는 실제 평가 데이터가 없는 예시다. 임의 점수를 DB에 넣거나 AI 추측을 사실처럼 표시하지 않는다. 확인된 특징을 표시하고 미등록 정보는 비워 둔다. 앞서 보류한 푸시 발송과 보호소 담당자 신규 화면은 이번 범위가 아니다.

## DB·검증·운영

- V31은 `map_templates`에 `auto_assign=false`인 v2 두 행을 추가한다. 새 테이블·환경변수·강아지 생성 호출은 없다. 보호소 연결 수와 공개 상태는 유지한다.
- 총 런타임 파일 410개: 기존 240개 + 정원 161개 + 로딩 9개. PNG·JSON만 제공한다. 디자인 도구 코드·HTML·GIF·실제 강아지 이미지·ZIP은 제공하지 않는다.
- `backend/scripts/import_meeting_screen_bundles.py`는 승인 ZIP 두 개의 SHA-256을 검사하고 V31·인덱스를 재현한다. 이미 배포한 버전·마이그레이션은 수정하지 않는다.
- 회귀 검사는 atlas 일치, 정원 재구성 원본 해시, 로딩 120프레임 전후경 합성 원본 해시를 검사한다. DB 검사는 등록 JSON·410개 HTTP 바이트·해시 일치, 버전 조회, 권한, 기존 데이터 보존을 확인한다.
- 운영 반영은 기존 자동 배포의 백업·별도 DB 복원/마이그레이션 리허설 이후 수행한다. PR 병합과 운영 확인은 별도로 기록한다.
- 프론트에서 새 엔드포인트를 호출하고 UI에 합성하는 작업은 별도다. 서버 테스트 통과는 실제 RN 화면 연결 완료를 의미하지 않는다.

작업 카드: [B-110](https://www.notion.so/4075b2d1a55f8095b3c0ed5ea7dfa444). 선행 B-108/B-109.
