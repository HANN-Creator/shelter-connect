# 커뮤니티 API · B-36

개발 기본 주소: `https://shelter-connect-dev.onrender.com`. Swagger: `/swagger-ui/index.html`.
이 문서는 병합 코드의 계약이다. 실제 개발 DB/Render 적용 여부와 실행 SHA는 노션 배포 기록을 확인한다.

보호소·강아지 탐색은 비로그인으로 열어 두고 **커뮤니티 전체는 로그인 후** 사용한다. Supabase access token으로 `POST /v1/me`까지 연결된 활성 계정이 필요하다. 본문은 일반 텍스트로 표시하고 HTML로 렌더링하지 않는다. 사람 간 문의는 후속 B-37 API로 연결한다.

## 화면별 요청

모든 경로는 `/v1` 접두어. 성공은 `200`, 단건은 `{data: ...}`, 목록은 `{data: [...], nextCursor: null|"..."}`다.

| 요청 | 화면 / 역할 |
| --- | --- |
| GET /community/posts | 목록, 홈 소식. `q` 제목·본문 검색, `region` 지역 접두어, `category` |
| GET /community/posts/{postId} | 상세. 작성자의 임시저장·숨김 상태 확인도 가능 |
| GET /me/community-posts | 내 글·임시저장. `publication=DRAFT/PUBLISHED` 또는 생략, `q` |
| POST /community/posts | 작성 / 임시저장 / 즉시 등록 |
| PATCH /community/posts/{postId} | 본인 글의 내용 전체 교체. 필수 `version` |
| POST /community/posts/{postId}/publish | 임시저장 등록. `{version}` |
| PUT /community/posts/{postId}/status | 글 상태 시트. `{version,status}` |
| DELETE /community/posts/{postId}?version=1 | 본인 글 숨김 삭제. 실제 행과 문의 맥락 보존 |
| GET /me/community-region | 커뮤니티 지역. 미설정 `regionLabel:null` |
| PUT /me/community-region | `{regionLabel:"춘천시 효자동"}` 또는 null. 현재 보호소와 독립적 |
| GET /community/posts/{postId}/comments | 댓글·제보, `kind=COMMENT/SIGHTING` 또는 생략 |
| POST /community/posts/{postId}/comments | 댓글 / 답글 / 목격 제보 |
| DELETE /community/posts/{postId}/comments/{commentId} | 본인 댓글 삭제. 답글과 삭제 표식 보존 |
| POST /community/posts/{postId}/reports | 신고 접수. 타인 글만, 사용자·글당 1건 |
| GET /operations/community-reports | OPERATOR 전용, `status=PENDING/HIDDEN/DISMISSED` |
| PUT /operations/community-reports/{reportId} | OPERATOR 검토, `{version,action:"HIDE"|"DISMISS",note}` |
| POST /community/media | `multipart/form-data`, `clientRequestId`와 `file` 두 항목 |
| GET /community/media/{mediaId} | 권한 검사 후 `{id,url,expiresAt}`, URL 60초 |

목록 기본20·최대50, `nextCursor`를 `cursor`로 그대로 전달한다. 목록 검색어·분류·지역·계정이 바뀌면 커서를 버린다. 공개 글은 등록순 최신, 내 글은 생성순 최신, 댓글·신고는 생성순 오름차순이다. 댓글 답글은 `parentId`로 묶고 페이지를 추가해도 기존 항목을 유지한다.

## 작성·수정

```json
{
  "clientRequestId": "aa000000-0000-4000-8000-000000000001",
  "category": "LOST",
  "publication": "PUBLISHED",
  "title": "갈색 강아지를 찾고 있어요",
  "text": "가상 연결 검사에 사용하는 내용입니다.",
  "regionLabel": "춘천시 효자동",
  "location": {
    "label": "가상 공원 입구",
    "latitude": 37.8,
    "longitude": 127.7,
    "occurredAt": "2026-09-30T00:00:00Z"
  },
  "features": ["갈색 털", "파란 목줄"],
  "mediaIds": []
}
```

- `category`: `LOST` 찾고 있어요, `FOUND` 발견했어요, `NEIGHBOR_NEWS` 동네 소식.
- `publication`: `DRAFT` 임시저장, `PUBLISHED` 등록. 임시저장은 제목·본문·지역을 비워도 된다. 등록하려면 세 항목 모두 필요하며 찾기/발견은 장소와 시각도 필요하다.
- 제목50자, 본문1,000자, 지역100자, 장소200자, 특징 최대8개·각20자, 사진 최대5개.
- 좌표는 두 개를 함께 보내거나 생략한다. 위치 동의·지오코딩·지도 SDK는 앱 담당. 좌표나 시각을 추측해서 채우지 않는다. 미래 시각은 5분의 기기 오차만 허용한다.
- 재시도에는 같은 `clientRequestId`와 같은 최초 내용을 전송한다. 중복 생성은 하지 않으며 최초 내용이 다르면 `409 REQUEST_ID_CONFLICT`다. 삭제한 요청을 재사용하면 `409 RESOURCE_DELETED`다.
- PATCH는 `clientRequestId`, `publication`을 제외하고 `version`을 넣어 **전체 내용**을 보낸다. 생략한 선택 항목은 비워진다. 이미 등록한 글의 분류는 고정이다. `409 VERSION_CONFLICT`이면 최신 내용을 다시 받아 비교한다.
- 응답은 `id, authorId, authorName, mine, category, publication, status, version, content, hidden, createdAt, publishedAt, updatedAt, commentCount, sightingCount`다. `content`에 위 제목·본문·지역·장소·특징·사진 ID가 들어간다.
- 목록의 **제보 수는 sightingCount**다. 신고자의 정보나 신고 건수로 대체하지 않는다. `mine`이 true일 때만 수정·상태 버튼을 표시하고 서버도 다시 검사한다.

## 상태와 댓글·제보

| 분류 | 허용 상태 |
| --- | --- |
| LOST | ACTIVE / REUNITED / CLOSED |
| FOUND | ACTIVE / REUNITED / CLOSED / TRANSFERRED |
| NEIGHBOR_NEWS | ACTIVE / CLOSED |

`ACTIVE`의 찾기/발견 글만 새 목격 제보를 받는다. 종료·가족 만남·인계는 기존 글·댓글·문의 기록을 보존한다. 다시 찾기를 시작할 때 작성자가 현재 version으로 `ACTIVE`를 선택할 수 있다. 상태 변경·신고 숨김과 새 제보는 같은 글을 잠가 경합 시 최신 상태로 판단한다.

```json
{
  "clientRequestId": "aa000000-0000-4000-8000-000000000002",
  "kind": "SIGHTING",
  "text": "공원 입구에서 봤어요.",
  "location": {"label":"가상 공원 입구","occurredAt":"2026-09-30T00:00:00Z"},
  "mediaIds": []
}
```

댓글은 `kind:COMMENT`, 답글은 같은 글의 최상위 댓글/제보 `parentId`를 추가한다. 답글 중첩은 1단계, 제보에는 parentId를 넣지 않는다. 본문1,000자, 사진 최대1개. 목격 위치와 시각·사진은 커뮤니티에 공개된다는 점을 작성 화면에 표시한다. 삭제/사용 중지된 댓글은 `deleted:true, content:null, authorId:null` 표식으로 받고 답글은 유지한다.

## 사진과 신고

1. 사진을 multipart로 올려 `{id,state:"READY",byteSize}`를 받는다. URL·버킷·object key를 클라이언트가 지정할 수 없다.
2. 글/제보의 mediaIds에 본인 업로드 ID를 넣는다. 다른 글에 묶인 사진이나 타인 사진은 거절한다.
3. 렌더링 직전에 media ID로 주소를 요청한다. 업로더, 현재 글을 읽을 수 있는 로그인 사용자 또는 게시된 글을 검토하는 운영자만 발급받는다. 게시 전 임시저장 사진은 운영자도 조회할 수 없다. 숨김·삭제·댓글 삭제는 일반 사용자의 새 발급을 막는다. 게시된 글의 사진은 운영 검토 예외를 유지한다.
4. 서명 URL은 60초 후 만료된다. 영구 저장하지 말고 필요하면 API를 재호출한다. 이미 발급한 URL은 권한 변경 후에도 만료까지 열릴 수 있다.

PNG/JPEG만, 원본·변환본 5MiB 이하, 입력 최대1,600만 화소/한 변8192px, 출력 최대2048px PNG로 재인코딩한다. EXIF 등 원본 메타데이터는 저장하지 않는다. 사용자별 24시간20장·누적100MiB로 무제한 업로드를 막는다. 실패한 업로드는 같은 ID/사진으로 재시도한다. 예약된 업로드도 한도에 포함되며 미첨부 사진 정리는 운영자가 소유·참조를 확인해 수행한다. 자동 수집과 PixelLab 생성은 이 업로드에서 실행하지 않는다.

신고 사유는 `SPAM / FALSE_INFORMATION / ABUSE / PRIVACY / OTHER`. `details`는 선택1,000자, OTHER일 때 필수다. 신고 접수만으로 글을 자동 차단하지 않는다. 운영자가 HIDE/DISMISS와 검토 사유를 기록한다. 신고자·설명은 일반 작성자나 다른 이용자에게 노출하지 않는다. 운영 권한은 서버 DB에서 확인한다.

## DB·설정·검증

- V10: 게시글·댓글/제보·사진 예약·신고 테이블 4개, 개인 설정의 community_region. 기존 데이터를 수정하지 않는다. 모든 테이블 RLS·anon/authenticated/PUBLIC 차단, 서버 실행 계정에 필요한 SELECT/INSERT/UPDATE만 부여.
- `COMMUNITY_MEDIA_ENABLED=true`, `COMMUNITY_MEDIA_BUCKET=community-media`, 기존 서버용 `SUPABASE_SECRET_KEY`. 버킷은 반드시 **private**. 꺼진 환경의 업로드/서명은503이며 텍스트 글은 사용 가능하다. 앱에 서버 키를 전달하지 않는다.
- 기본 인증401, 미등록·정지/운영권한403, 다른 사람 자료·비공개404, version/상태/사진 소유 충돌409, 사진크기413, Content-Type415, 요청/사진한도429, 저장소503.
- 새 HTTP/PostgreSQL 13개와 경합4개: 게스트 경계, 임시저장, 공개 검증, 중복/충돌, 페이지 이동, 작성자 분리, 종료 상태 재확인, 신고 운영 권한, 사진 공개 조건. 저장소/이미지 경계5개, Swagger 전체 계약 검사.
- 실제 보호소 사진·실종 글은 게시하지 않는다. 테스트는 가상 기록·생성한 단색 이미지로 실행한다. RN 실제 연동·푸시 알림·지도 SDK는 앱 연결 단계다.
