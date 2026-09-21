# 사진 저장소 연결 · B-12

기존 사진 조회 API가 실제 Supabase Storage를 쓰도록 연결하는 작업이야. 대상은 DB와 같은 `shelter-connect-dev` 프로젝트야. 앱에서는 사진 저장소를 직접 열지 않고, 기존 `GET /v1/dogs/{dogId}/photos` 응답의 `url`을 쓰면 돼.

## 현재 확인한 상태

2026.09.21 기준으로 연결 설정과 검증 도구를 준비했어. **서버 키 입력 전이므로 실제 사진 주소 발급·다운로드는 아직 확인하지 않았어.**

| 확인 항목 | 결과 |
| --- | --- |
| 기존 저장소 | 버킷 0개, 앱 사진 행 0개 |
| 직접 접근 정책 | `storage.objects` RLS 활성, anon·authenticated·public 대상 정책 0개 |
| 실행 설정 검사 | Python 9개 통과 |
| 서버 테스트·빌드 | Java 테스트 265개 통과, 실행 JAR 빌드 성공 |
| 비공개 버킷·실제 파일 | 서버 키 입력 후 준비·검증 필요 |

저장소와 정책 확인은 지정 DB에서 읽기 전용으로 했어. 실제 로그인 사용자의 대화 → 사진 API 전체 흐름, 보호소 사진 등록과 배포는 별도야.

## 로컬 서버 키 넣기

`backend/`에서 B-11의 `.env.supabase`를 그대로 쓰고, Storage 키만 별도 파일에 넣어줘.

```sh
cp -n .env.storage.example .env.storage
chmod 600 .env.storage
```

Supabase의 같은 프로젝트에서 **Settings → API Keys → Secret keys**의 기존 서버 키를 복사해서 `SUPABASE_SECRET_KEY=` 뒤에 넣어. `sb_secret_`로 시작하는 키이고 DB 비밀번호나 앱용 publishable 키와는 달라. 값에 따옴표를 붙이거나 이 파일을 `source`로 실행하지 않아.

```dotenv
SUPABASE_SECRET_KEY=<같은 프로젝트의 기존 서버 키>
PHOTO_STORAGE_BUCKET=dog-photos
PHOTO_STORAGE_TIMEOUT_SECONDS=5
```

`.env.storage`는 Git에서 제외돼. 실제 키나 서명 URL을 PR·노션·로그에 복사하지 않아. RN에는 이 키를 전달할 일이 없어.

## 처음 저장소를 준비할 때

Java 21을 설정하고 아래 명령을 실행해.

```sh
python3 scripts/run_supabase.py --with-photos --check-config
python3 scripts/check_photo_storage.py --prepare
```

`--check-config`는 형식만 검사하고 외부에 접속하지 않아. `--prepare`는 지정 프로젝트에 아래 구성을 준비하고 실제 연결을 검사해.

- `dog-photos`가 없으면 비공개 버킷을 생성해. 파일당 최대 5 MiB, JPEG·PNG·WebP·GIF만 허용해.
- 버킷이 이미 있으면 설정을 확인해. 공개 상태이거나 제한이 다르면 멈추고, 기존 설정을 덮어쓰지 않아.
- `00000000-0000-4000-8000-000000000012/connection-check.png`에 직접 만든 8×8 테스트 이미지를 넣어. 같은 파일이 있으면 덮어쓰지 않고 내용이 같은지 확인해.
- 실제 서버의 `SupabasePhotoStorage` 코드로 60초 서명 URL을 발급하고, 키 없이 그 주소에서 내려받은 PNG가 원본과 같은지 비교해.
- 공개 URL과 인증 없는 직접 다운로드가 거절되는지 확인해. 키와 서명 URL은 출력하지 않아.

이 이미지는 저장소 검사 전용이라 강아지 프로필이나 `shelter.dog_photos`에 등록하지 않아. 기존 강아지·사진·대화·사용 허가 기록, 로그인 권한과 Storage 정책도 바꾸지 않아. 파일은 반복 검사용으로 버킷에 남아.

다음부터는 `--prepare` 없이 검사하면 돼. 이때는 버킷이나 파일을 생성하지 않아.

```sh
python3 scripts/check_photo_storage.py
```

검사 도구는 개발용 코드에만 들어 있고 서버 JAR에 포함되지 않아. 일반 테스트·빌드·CI에서도 실제 저장소 검사를 자동 실행하지 않아. Storage 검사에는 DB 비밀번호를 전달하지 않고, 별도의 PostgreSQL 연결도 열지 않아.

## 사진 기능을 켜서 서버 실행하기

```sh
./gradlew bootJar
python3 scripts/run_supabase.py --with-photos
```

`--with-photos`를 빼면 이전처럼 사진 저장소 기능은 꺼져 있어. DB 조회만 확인할 때는 `--read-only --with-photos`를 함께 쓰면 돼. 마이그레이션·SQL 초기화·AI는 계속 꺼져 있고 서버는 localhost에서 실행돼. `Ctrl+C`로 종료하면 돼.

서버가 정상 실행된다는 사실만으로 사진 연결을 확인한 건 아니야. 사진이 없는 목록은 Storage를 호출하지 않으므로, 반드시 위 전용 검사에서 실제 이미지 다운로드까지 확인해줘.

## 실제 강아지 사진을 넣을 때

파일 경로는 `<강아지 UUID>/<파일명>`을 써. 저장소에 파일만 올리면 앱 목록에는 나오지 않아. DB에 강아지·버킷·경로·순서·설명과 실제 확인자가 확인한 사용 허가를 함께 등록해야 해. 테스트 때문에 허가 정보를 꾸며서 등록하지 않아.

사진 등록·업로드 API는 아직 없어. 현재 서버는 [사진 조회 API](photo-read-api.md)의 로그인·대화·공개·사용 허가 조건을 그대로 검사해. 실제 보호소 사진이 준비되면 등록 경로와 담당자 권한까지 다음 작업으로 연결하면 돼.

비공개 버킷과 만료되는 서명 URL은 [Supabase 다운로드 안내](https://supabase.com/docs/guides/storage/serving/downloads)를 따랐어. [버킷의 형식·크기 제한](https://supabase.com/docs/guides/storage/buckets/creating-buckets)과 [서버 키 사용 방식](https://supabase.com/docs/guides/getting-started/api-keys)도 참고할 수 있어.
