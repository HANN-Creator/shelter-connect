# 보호소 공용 맵 3종 — B-108

최근 독대 정원·로딩 v2와 최신 버전 조회는 [B-110 연결 명세](meeting-screen-api.md)를 따른다. 아래 v1 보호소 맵은 계속 제공한다.

한 보호소에서 햇살 운동장, 숲속 산책길, 물가 쉼터를 모두 사용한다. 공용 맵은 한 번 저장하고 기존·신규 보호소에 세 개를 연결한다. 보호소 공개 상태와 강아지 데이터는 변경하지 않는다.

노션 카드: https://app.notion.com/p/4035b2d1a55f8079a091c1db679fe3dd

## 저장 구조

- `shelter.map_templates`: 맵 ID·버전·이름·크기·원본 ZIP 해시·manifest·layout·파일별 SHA-256/길이/유형. 보호소용 맵은 `auto_assign=true` 3행이며 640×640이다. V30의 독대 정원은 `auto_assign=false`로 별도 등록한다.
- `shelter.shelter_maps`: 보호소와 공용 맵의 연결, 표시 순서, 기본 맵. 기본은 햇살 운동장. 기존 보호소를 V29에서 연결하고 새 보호소 INSERT 시 트리거로 연결한다. 공공 API를 통한 보호소 생성에도 적용된다.
- 보호소 맵 PNG·JSON 204개와 독대 정원 36개는 `backend/src/main/resources/map-assets/1.0.0/`에서 JAR에 포함한다. DB에 바이너리를 넣지 않는다. DB 백업과 해당 릴리스 JAR로 같은 버전의 맵을 복원할 수 있다.
- 기존 `shelters.map_key`와 조회 응답의 `mapKey`는 호환을 위해 그대로 유지한다. 신규 프론트는 아래 목록을 사용한다.
- 런타임 계정은 맵 테이블 조회만 가능하다. 자동 연결은 고정 search_path의 트리거 함수로 제한하며 일반 클라이언트 DB 역할에는 접근을 허용하지 않는다.

## API

`GET /v1/shelters/{shelterId}/maps` — 로그인 없이 공개·승인된 보호소만 조회, 비공개/없는 보호소 404. 응답 `data` 배열은 표시 순서로 정렬된다.

```json
{"data":[{"id":"01-sunny-meadow","version":"1.0.0","title":"햇살 운동장","width":640,"height":640,"sortOrder":0,"isDefault":true,"assetBaseUrl":"/v1/map-assets/1.0.0/01-sunny-meadow/","manifestUrl":"/v1/map-assets/1.0.0/01-sunny-meadow/manifest.json","layoutUrl":"/v1/map-assets/1.0.0/01-sunny-meadow/map/layout.json","manifestSha256":"<sha256>","layoutSha256":"<sha256>"}]}
```

실제 배열에는 맵 3개가 들어간다. URL은 **프론트 웹사이트 원점이 아닌 API 원점**을 기준으로 해석한다. 예: `https://api.shelterconnect.win` + `manifestUrl`.

`GET /v1/map-assets/{version}/{mapId}/{file}` — 원본 PNG·JSON 반환. manifest 안의 상대 파일 경로를 `assetBaseUrl`에 붙인다. 버전 고정, 1년 public immutable 캐시, ETag/SHA-256 제공. 배포 시 모든 파일의 SHA-256과 길이를 검사하고, 등록된 파일만 제공한다. 공용 디자인이므로 보호소별 비공개 정보를 포함하지 않는다. HTML 미리보기, 소스 코드, 임의 서버 파일은 제공하지 않는다. 원본 manifest의 `preview`는 오프라인 전달 묶음용이며 API에서 제공하지 않는다.

렌더링: nearest sampling, 좌상단 원점의 월드 좌표, ground → water mask → plants → depth순 objects/actors. 다리/데크는 ground에 이미 포함되어 있어 중복 배치하지 않는다. 물·풀·갈대는 8프레임 × 250ms. manifest의 크기·anchor·drawOrder와 layout의 obstacles/spawn/slots를 사용한다. 움직이는 강아지의 매 프레임 좌표는 DB에 저장하지 않는다.

## 원본과 재현

노션 **🎨 화면 톤과 에셋** 첨부 `shelter-connect-three-maps.zip`:
https://app.notion.com/p/3e15b2d1a55f81f0a0a0f523d29a2c5c

ZIP SHA-256: `7eb2bbdc9f0172ff7fc1f2d5568ad6272fae6568b872ca974403b8317f09c552`.

프로젝트에서 제작한 원본 맵. Sprout Lands는 분위기 참고이며 외부 에셋 파일을 복제하지 않았다. 받은 ZIP의 코드/HTML은 실행하지 않으며 런타임 PNG·JSON만 포함한다.

오프라인 재현: `python3 backend/scripts/import_map_bundle.py <검증된-ZIP-경로>`.
이 명령은 파일과 V29를 생성할 뿐 DB에 접속하지 않는다. 이미 배포한 버전이나 Flyway 마이그레이션은 다시 편집하지 않고 새 버전·마이그레이션으로 변경한다.

## 배포·검증

V29는 테이블 2개·트리거·기존 보호소 연결을 추가한다. Ubuntu 자동 배포의 마이그레이션 사전 백업과 복제 DB 리허설을 통과해야 적용된다. 백업이 비활성화된 상태에서 수동으로 마이그레이션을 우회하지 않는다.

로컬·CI: V28 데이터 보존 업그레이드, 중복 적용 없음, 신규 공공 보호소의 최소 권한 자동 연결, 공개 경계, 204개 파일 해시 및 DB JSON 일치, HTTP 캐시·미등록 파일 차단을 검증한다.

실서버 적용 후 V29 이력·3개 템플릿·보호소별 3개 연결과 공개 API/PNG를 별도로 확인한다. 프론트에서 새 API로 실제 맵을 렌더링하는 연결은 별도 작업이다.

### 자체 호스팅 PostgreSQL 역할 호환

2026-10-24 Ubuntu의 V28 복제 DB 리허설에서 Supabase 전용 `anon` 역할이 없어 V29의 `REVOKE`가 SQLSTATE `42704`로 실패했다. 운영 마이그레이션과 JAR 교체 전에 중단돼 기존 서버는 유지됐다. V29는 `PUBLIC`·런타임 권한 제한을 유지하고, `anon`·`authenticated`가 존재할 때만 해당 역할의 권한을 회수한다. 사용하지 않는 클라이언트 역할을 생성하지 않는다.

운영에 아직 적용되지 않은 V29 및 ZIP에서 이를 재생성하는 템플릿만 보완했다. 이미 적용된 이력을 `repair`하거나 체크섬 검증을 끄지 않는다. V28→V29 회귀는 Supabase 역할 있음/없음 양쪽에서 기존 보호소·강아지 보존과 연결을 검사하며, 역할이 있을 때는 기본 ACL로 부여된 권한도 회수되는지 검증한다. 역할 이름을 잠시 바꾸는 검사는 로컬의 전용 `shelter_test` 클러스터에서만 순차 실행하고 `finally`에서 원래 이름으로 복구한다.
