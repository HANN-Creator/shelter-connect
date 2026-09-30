# 개인 기능과 주변 보호소 API · B-35

설계 기준은 2026-10-01 승인 시안이다. 기존 `/v1/me`, 보호소·강아지·AI 대화 API는 유지한다.
보호소·맵·공개 강아지 프로필은 로그인 없이 조회한다. 강아지 AI 대화, 개인 기록, 커뮤니티는 로그인 후 사용한다.
사진 원본의 기존 사용 허가·완료된 AI 답변 조건은 바꾸지 않았다.

## 회원가입·로그인

이메일/비밀번호 가입·이메일 확인·로그인·비밀번호 재설정·토큰 갱신은 프론트의 Supabase Auth SDK에서 처리한다.
Spring에 비밀번호나 refresh token을 보내지 않는다. 유효한 access token으로 `POST /v1/me` 후 프로필을 설정한다.

| API | 요청 / 응답 |
| --- | --- |
| `PATCH /v1/me/profile` | `{displayName}`: 공백 제거 후 1~30자. 닉네임은 중복 허용하며 사용자 식별은 id로 한다. role/userId 등 추가 필드는 400 |
| `GET /v1/registration-policy` | 공개된 `termsVersion`, `privacyVersion`, `available` |
| `PUT /v1/me/consents` | `{termsVersion, privacyVersion, termsAccepted:true, privacyAccepted:true}` |
| `GET /v1/me/consents` | 현재 문서 버전의 동의 기록 또는 `data:null` |

동의 날짜는 서버가 기록한다. 같은 버전 재요청은 최초 시각을 유지한다. 현재 버전 불일치 409, 동의 체크 누락/false 400.
`TERMS_VERSION`/`PRIVACY_VERSION`은 해당 문서를 실제 공개한 후 같은 버전으로 설정한다. 미설정이면 available=false, 동의 저장은 503이다.
법적 문구 작성·공개는 이 API 구현과 구분한다. 기존 가입/로그인을 중단하거나 과거 사용자를 임의로 동의 처리하지 않는다.
로그인이 필요한 행동에서 안내를 열고 성공 후 원래 dogId/postId/작성 중 화면으로 돌아가는 것은 프론트 책임이다.

## 저장한 친구·현재 보호소

| API | 동작 |
| --- | --- |
| `GET /v1/me/saved-dogs` | 최신 저장순, `q`(강아지/보호소), `shelterId`, `limit`, `cursor` |
| `GET /v1/me/saved-dogs/{dogId}` | `{dogId,saved}` |
| `PUT /v1/me/saved-dogs/{dogId}` | 공개 중인 친구 저장. 여러 번 눌러도 중복/순서 변경 없음 |
| `DELETE /v1/me/saved-dogs/{dogId}` | 저장만 해제. 대화·입양 메모 유지 |
| `GET /v1/me/preferences` | `{currentShelterId}` 또는 null |
| `PUT /v1/me/preferences` | `{currentShelterId: UUID 또는 null}`. 승인·공개 보호소만 선택 |

저장 목록: dogId/dogName/shelterId/shelterName/avatarKey/adoptionStatus/savedAt/sessionId.
공개가 철회되면 목록에서 숨긴다. 기존 저장 상태와 대화는 삭제하지 않는다. 저장 취소는 비공개 전환 후에도 가능하다.
선택 보호소가 비공개로 바뀌면 currentShelterId는 null이다. 비로그인 사용자의 임시 보호소 선택은 기기에 보관한다.
`avatarKey`는 원본 사진 URL이 아니다. 실제 캐릭터는 기존 `/v1/dogs/{dogId}/assets` 명세로 가져온다.

## 강아지 대화 목록

`GET /v1/me/dog-conversations?q=&savedOnly=false&limit=20&cursor=`

sessionId/dogId/dogName/shelterName/avatarKey/available/saved/lastMessage/lastMessageRole/updatedAt.
최근 활동순이다. 아직 대화하지 않은 저장한 친구는 저장 목록에서 sessionId=null인 항목을 활용한다.
기존 방은 sessionId로 열고, 새 방은 기존 `POST /v1/dogs/{dogId}/chat-sessions`로 생성/재사용한다.
공개 철회 시 현재 이름·보호소·에셋을 숨기고 본인의 과거 대화만 유지한다. 읽음 숫자와 사람 간 문의는 별도 API다.

## 주변 보호소

`GET /v1/shelter-discovery?q=&region=&latitude=37.8&longitude=127.7&limit=20&cursor=`

공개 API. 두 좌표를 함께 전달하면 구면 직선거리(m)순, 동률 id순이다. 좌표가 없는 보호소는 마지막이며 distanceMeters=null.
기준 좌표를 생략하면 id순/거리 null. q는 이름·주소 부분 검색, region은 지역 접두어 검색이다.
응답: id/name/region/address/latitude/longitude/distanceMeters/mapKey/dogCount.
위치 권한·현재 위치·지역 선택/주소 검색은 프론트 지도 제공자와 연결한다. 기준 좌표를 개인 설정이나 서버 로그에 저장하지 않는다.

## 공통 연결·보안

`{data,nextCursor}` 목록, `{data}` 단건. limit 1~50, 기본20. nextCursor를 그대로 전달하고 검색·좌표·필터 변경 시 초기화한다.
각 커서는 사용자/검색 조건에 묶이고 모든 조회에서 권한을 다시 확인한다. 실시간 새 글/활동으로 정렬이 바뀌면 첫 페이지를 새로고침한다.
오류는 code/message/requestId. 기존 64KiB 본문·요청 횟수 제한을 유지한다. 데이터베이스 접근은 서버만 수행한다.

V9는 user_preferences/user_consents/saved_dogs 3개 테이블과 목록 인덱스를 추가한다. 기존 테이블 데이터 변경 없음.
RLS, 클라이언트 접근 차단, 최소 실행 계정 권한을 적용한다. 동의 기록은 실행 계정으로 변경/삭제할 수 없다.
원격 DB 적용·배포 결과는 PR 및 노션에 별도로 기록한다. 코드 병합 자체가 배포 완료를 뜻하지 않는다.
