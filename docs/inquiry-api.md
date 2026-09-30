# 이웃 문의 연결 명세 (B-37)

강아지 AI 대화와 별도인 게시글 작성자–문의자 1:1 대화다. Supabase 로그인 후 `POST /v1/me`로 등록된 활성 사용자만 사용할 수 있다. 보호소와 강아지의 공개 탐색은 로그인 없이 유지한다. 문의 목록의 `counterpart.available`은 계정 이용 가능 여부이며 온라인 상태가 아니다.

## API

| 메서드·경로 (`/v1` 기준) | 동작 |
| --- | --- |
| `POST /community/posts/{postId}/inquiries` | 본문 없이 문의 시작. 게시글·문의자 조합으로 기존 방 재사용 |
| `GET /inquiry-rooms` | `q`, `unreadOnly`, `limit`(기본 20/최대 50), `cursor`로 목록·검색 |
| `GET /inquiry-rooms/{roomId}` | 상대, 게시글 요약, 마지막 메시지, 읽음 상태 |
| `GET /inquiry-rooms/{roomId}/messages` | 최신 기록 또는 순서 기반 이전 기록·새 메시지 조회 |
| `POST /inquiry-rooms/{roomId}/messages` | 텍스트·사진·위치 전송 |
| `PUT /inquiry-rooms/{roomId}/read` | 실제 화면에 표시한 마지막 `upToSequence`까지 읽음 처리 |

목록 응답은 `{data, nextCursor, unreadRoomCount}`. `unreadRoomCount`는 검색·필터와 무관한 본인의 미확인 **방 수**다. 개별 방의 `unreadCount`는 상대가 보낸 미확인 메시지 수다. 목록은 최근 메시지 활동 순이며 읽음 처리만으로 정렬이 바뀌지 않는다. 커서는 사용자·검색·필터에 묶인다. 갱신 시 첫 페이지부터 새로 받는다.

방 응답에는 `id`, `postId`, `counterpart {id,nickname,avatarKey,available}`, `post {id,available,title,category,status,thumbnailMediaId}`, `lastMessage`, `unreadCount`, `lastSequence`, `readSequence`, `counterpartReadSequence`, `canSend`, `updatedAt`가 있다. 현재 사용자별 아바타 업로드 기능은 없으며 `avatarKey=null`이면 공통 기본 그림을 표시한다. 숨김·삭제된 글은 제목·상태·사진을 가린 대체 요약을 표시하고 대화 기록은 유지한다.

## 전송·사진·위치

```json
{"clientMessageId":"550e8400-e29b-41d4-a716-446655440000","kind":"TEXT","text":"어디에서 보셨나요?"}
```

`clientMessageId`는 기기에서 생성하는 UUID다. 네트워크 재시도에는 같은 ID와 같은 내용을 사용한다. 동일 요청은 기존 메시지를 반환하고 다른 내용이면 409 `MESSAGE_ID_CONFLICT`. `senderId`, 순서, 생성 시각은 서버가 정한다.

- `TEXT`: 공백 제외 1–1,000자. `mediaId`·`location` 금지.
- `IMAGE`: 먼저 [커뮤니티 미디어 API](community-api.md) `POST /community/media`로 업로드한 본인 `mediaId`를 전달. 설명 `text`는 선택. `location` 금지.
- `LOCATION`: `{label,latitude,longitude}` 필수. 위경도 범위 검사. `text` 선택, `mediaId` 금지. 실제 위치 접근은 앱이 사용자 동의 후 요청하며 서버는 임의로 수집하지 않는다.

미디어를 문의에 연결한 뒤에는 다른 문의방이나 공개 게시글에 재사용할 수 없다. 사진은 같은 `GET /community/media/{mediaId}`로 60초 서명 URL을 받지만 문의 참여자만 접근한다. 운영자라도 제3자라면 문의 사진·메시지 조회 불가. 클라이언트에 Storage 서버 키를 전달하지 않는다. 주소를 로그에 남기거나 영구 저장하지 않고 필요 시 재발급한다. 이미 발급한 URL은 만료 전 최대 60초 남을 수 있다.

## 순서·읽음·화면 연결

메시지는 `{id,roomId,sequence,senderId,mine,kind,text,mediaId,location,createdAt}`. `sequence`는 방마다 1부터 증가하고 동시 전송도 중복되지 않는다.

1. 방을 열면 `GET .../messages?limit=20`: 최신 20개가 **오름차순**으로 온다. 더 오래된 기록은 `olderBeforeSequence`를 `beforeSequence`로 전달한다.
2. 새 메시지는 현재 화면의 최대 순서를 사용해 `GET .../messages?afterSequence=42`. 결과는 오름차순, 다음 조회는 `nextAfterSequence` 사용. `hasMore=true`이면 연속 조회한다. 최초 전체 순차 조회는 `afterSequence=0`. `beforeSequence`와 `afterSequence`는 함께 보낼 수 없다.
3. 실제 메시지를 보여준 다음 `PUT .../read`에 `{"upToSequence":42}`를 보낸다. GET 요청만으로는 읽음 처리하지 않는다. 과거 순서 재요청은 읽음 상태를 되돌리지 않으며 미래 순서는 400.
4. 앱이 화면에 있는 동안 새 메시지·방 정보를 주기적으로 조회하고, 화면을 벗어나면 중단한다. 푸시·WebSocket·지도 SDK는 이번 범위에 포함하지 않는다. 위치 응답은 지도 표시·지도앱 연결에 사용할 수 있다.

## 접근·종료·DB

자신의 글에는 문의방을 만들 수 없다(400). 제3자의 방은 존재 여부를 숨겨 404. 종료된 글(`REUNITED`, `CLOSED`, `TRANSFERRED`)은 새 문의자를 받지 않지만 기존 방을 다시 열고 연락을 이어갈 수 있다. 글이 숨김/삭제되거나 상대 계정이 중지되면 `canSend=false`, 새로운 메시지는 409 `INQUIRY_READ_ONLY`; 기존 기록과 읽음 처리는 유지한다. 이미 성공한 메시지 재전송은 기존 결과를 반환한다. 상대 계정이 중지되면 닉네임은 `이웃`으로 가린다.

V11은 `inquiry_rooms`, `inquiry_messages`, 미디어의 문의방 연결 및 관계 제약을 추가한다. RLS 활성화, `anon`·`authenticated` 직접 접근 차단, 서버 런타임만 허용. 메시지는 추가만 가능하고 런타임 UPDATE/DELETE 권한이 없다. 서버 트랜잭션이 참여자·게시글 상태·계정 상태를 다시 확인한다. 비공개 대화 내용·좌표·서명 URL은 로그/노션에 기록하지 않는다.

관련 문서: [개인·탐색](personal-discovery-api.md), [커뮤니티](community-api.md). 배포·원격 DB 적용 여부는 PR과 노션에 별도로 기록한다.
