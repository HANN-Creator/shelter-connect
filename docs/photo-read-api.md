# 사진 조회 · B-08

강아지 목록과 기본 프로필은 계속 도트 정보만 보내. 실제 사진은 로그인한 뒤 별도의 API로 조회하면 돼. 이번 구현에서는 기존의 ‘대화 후 사진 보기’ 흐름을 따라 **본인이 해당 강아지의 답변을 한 번 이상 받은 뒤** 사진을 열도록 했어. 구체적인 횟수에 대한 답변이 없어 1회로 구현한 기준이며, C-02 전체 합의를 완료 처리한 것은 아니야.

## 요청과 응답

```http
GET /v1/dogs/{dogId}/photos?limit=20&cursor=<nextCursor>
Authorization: Bearer <access_token>
```

- `limit`: 기본 20, 1~50. 첫 페이지는 `cursor`를 생략해.
- 사진의 `sortOrder` 오름차순, 같은 순서의 비교 기준은 `id`야. DB에서는 같은 강아지의 순서 중복을 막아.
- `nextCursor`가 있으면 다음 요청의 `cursor`로 보내. 사용자나 강아지가 바뀌면 커서를 버려.
- 응답과 오류 모두 `Cache-Control: no-store`를 사용해.

```json
{
  "data": [
    {
      "id": "03100000-0000-4000-8000-000000000001",
      "dogId": "02200000-0000-4000-8000-000000000001",
      "sortOrder": 0,
      "caption": "산책 중인 봄이",
      "url": "https://example.supabase.co/storage/v1/object/sign/dog-photos/02200000-0000-4000-8000-000000000001/walk.jpg?token=example",
      "expiresAt": "2026-09-21T00:01:00Z"
    }
  ],
  "nextCursor": null
}
```

위 주소·ID·사진 설명은 형식 예시이고 실제 사진 링크가 아니야. 사진이 없거나 사용 허가가 확인된 사진이 없으면 `{"data":[],"nextCursor":null}`을 보내. `caption`이 미확인이면 null로 유지해. 출처 메모·허가 사유·확인자·원본 저장 필드는 응답에서 빼지만, 서명 URL 자체에는 파일 경로가 들어가므로 파일 이름에 개인정보를 넣으면 안 돼.

## 사진을 볼 수 있는 조건

1. 유효한 Supabase 토큰과 활성 서비스 사용자가 필요해.
2. 보호소가 승인·공개 상태이고, 강아지도 공개·미보관·AVAILABLE 또는 IN_PROGRESS여야 해.
3. 본인의 해당 강아지 대화방에 COMPLETED 사용자 메시지와 이를 참조하는 COMPLETED 답변이 있어야 해. 사용자 메시지만 저장했거나 답변 생성이 실패한 상태는 포함하지 않아. 다른 강아지나 다른 사용자의 대화도 포함하지 않아.
4. 사진의 `rightsStatus`가 GRANTED여야 해. UNKNOWN·REVOKED는 제외해.

짧은 인사나 보호소 확인 안내처럼 서버가 저장한 답변도 1회에 포함해. 이전에 닫힌 대화방의 완료된 대화도 인정해. 운영자나 보호소 담당자도 이 사용자용 API에서는 다른 사람의 대화로 조건을 대신 충족할 수 없어.

사진 파일 주소를 발급받기 전과 받은 후에 계정·공개·대화 조건을 검사해. 파일 정보나 사용 허가가 도중에 바뀌면 주소를 반환하지 않고 새로 조회하도록 안내해. 외부 저장소를 기다리는 동안 DB 연결이나 행 잠금을 유지하지 않아.

| HTTP / code | 프론트 처리 |
| --- | --- |
| 200 | 사진 표시. data가 비어 있으면 ‘사진 준비 중’ |
| 401 / UNAUTHENTICATED | 로그인 필요 또는 토큰 갱신 |
| 403 / ACCOUNT_NOT_REGISTERED | 먼저 POST /v1/me로 서비스 사용자 등록 |
| 403 / ACCOUNT_DISABLED | 중지된 계정 안내 |
| 403 / PHOTO_LOCKED | ‘먼저 이 친구와 이야기해 봐요’ 후 대화로 이동 |
| 404 / DOG_NOT_FOUND | 없거나 현재 공개하지 않는 강아지 |
| 400 / INVALID_REQUEST, INVALID_CURSOR | 입력 확인, 커서는 버리고 처음부터 조회 |
| 409 / PHOTO_SET_CHANGED | 사진 정보 변경 또는 잠금 충돌. 처음부터 다시 조회 |
| 403 / PHOTO_ACCESS_CHANGED | 조회 중 사용자 연결 변경. 다시 로그인 |
| 503 / PHOTO_STORAGE_NOT_CONFIGURED | 서버 저장소 연결 준비 중 |
| 503 / PHOTO_STORAGE_NOT_PRIVATE, PHOTO_STORAGE_INVALID | 버킷·파일 정보 설정 확인 필요 |
| 502 / PHOTO_STORAGE_UNAVAILABLE | 저장소 오류, 없는 파일 또는 잘못된 응답. 빈 목록으로 취급하지 않기 |
| 504 / PHOTO_STORAGE_TIMEOUT | 저장소 응답 시간 초과. 사용자가 다시 요청 가능 |

서명 URL은 60초 동안 유효하고 `expiresAt`은 보수적으로 계산한 만료 시각이야. 만료되면 URL을 재사용하지 말고 같은 페이지를 다시 조회해. 이미 표시된 사진을 60초마다 다시 받으라는 뜻은 아니야. 페이지 도중 순서나 사진이 바뀌면 목록을 새로 불러오는 게 좋아. 모든 페이지가 동일 시점의 스냅샷인 것은 아니야.

## Supabase Storage 연결

사진은 **비공개 버킷**에 넣어야 해. 서버는 조회마다 버킷이 비공개인지 검사한 뒤 한 페이지의 주소를 한 번에 발급해. 허용한 버킷과 해당 강아지 폴더만 사용하며, 클라이언트가 임의의 파일 경로나 만료 시간을 지정할 수 없어.

```dotenv
PHOTO_STORAGE_ENABLED=true
SUPABASE_SECRET_KEY=<서버에만 설정>
PHOTO_STORAGE_BUCKET=dog-photos
PHOTO_STORAGE_TIMEOUT_SECONDS=5
```

- 기존 `SUPABASE_URL`과 같은 프로젝트의 Storage를 사용해. 별도의 임의 호스트로 키를 보내거나 리다이렉트를 따라가지 않아.
- `SUPABASE_SECRET_KEY`는 `sb_secret_` 형식의 서버 전용 키를 사용해. RN·웹·GitHub·노션에 넣지 마. 키를 설정하지 않은 기본 상태는 비활성화야. 빈 사진 목록은 저장소 연결 없이 반환할 수 있어.
- 최신 Secret key는 JWT가 아니어서 `apikey` 헤더로 전달해. 사용자 Bearer 토큰을 저장소에 전달하지 않아.
- `storage_bucket`은 설정한 버킷, `storage_key`는 `<강아지 UUID>/<파일명>` 형식이야. 예: `02200000-0000-4000-8000-000000000001/walk-01.jpg`.
- JPG/JPEG/PNG/WebP/GIF 확장자를 허용해. 상위 폴더 이동, 빈 경로 조각, 제어 문자, 역슬래시, `%`, `?`, `#`는 허용하지 않아. 한글과 공백은 URL에서 인코딩해. 버킷의 MIME 제한도 이미지로 설정하고 실제 업로드 시 파일 내용 검증은 별도로 해야 해.
- 서버는 비공개 버킷 조회와 일괄 URL 발급에 각각 기본 5초(1~10초 설정 가능) 제한을 적용해. 응답 크기는 256 KiB까지이고 자동 재시도는 없어. 일부 파일이 없거나 발급에 실패하면 전체 요청을 오류로 반환해.
- 직접 접근용 Storage 정책을 공개로 풀면 앱의 대화 조건을 우회할 수 있어. 공개 버킷이나 일반 사용자가 파일을 바로 읽는 SELECT 정책을 추가하지 않는 구성이 전제야.

서명 주소는 소지한 사람이 만료 전까지 열 수 있어. 사진 사용 허가·공개 상태를 바꿔도 **이미 전달한 URL은 즉시 회수되지 않고 만료 전까지 유효할 수 있어.** 다시 발급할 때는 현재 조건을 검사해. 앱은 URL을 영구 저장·공유하지 않는 방식으로 연결해.

공식 기준: [비공개 파일과 서명 URL](https://supabase.com/docs/guides/storage/serving/downloads), [API 키와 서버 키 헤더](https://supabase.com/docs/guides/getting-started/api-keys), [Storage REST 규격](https://supabase.com/docs/reference/self-hosting-storage/retrieve-object-info).

## 적용·검증 범위

기존 `dog_photos`와 대화 테이블을 사용하고 새 마이그레이션은 없어. B-08 당시에는 실제 Supabase DB·Storage·사용 권한을 변경하지 않았어. 이후 B-10A에서 개발 DB의 V2·V3를 적용했으며 [적용 기록](supabase-development-db.md)에 정리했어. 실제 사진 Storage 설정은 여전히 별도야.

현재 샘플에는 실제 사진이 없고 작업 환경에 Storage 서버 키도 없어. 실제 사진 업로드, 사진 행·사용 허가 등록, 버킷 생성·정책 설정, 실제 파일 조회와 RN 화면 연결·배포는 남아 있어. 이 API는 조회만 구현하며 사진 등록·업로드 API는 포함하지 않아.

B-12에서 [별도 서버 키 설정과 실제 저장소 검사 방법](photo-storage-connection.md)을 준비했어. `--with-photos`로 로컬 서버의 저장소 기능을 켤 수 있고, 전용 검사에서는 서버와 같은 서명 코드를 사용해. 실제 연결 완료 여부는 해당 문서의 확인 결과를 보면 돼.

검증은 모의 Storage HTTP 서버와 PostgreSQL에서 진행해. 정렬·페이지·빈 사진·계정·대화 조건·사용 허가·비공개 상태, 발급 중 상태 변경, 외부 오류·잘못된 URL·시간 초과·응답 크기를 확인해. 실제 Supabase 파일 다운로드 성공과는 구분해.
