# 둘만의 정원 — B-109

강아지와 독대하는 화면의 정적 배경·사물 에셋. 2026-10-26 승인된 정원 시안의 분위기로 제작한 27개 스프라이트를 보관한다. 실제 앱 화면 교체는 별도 작업이다.

- 자료·원본 ZIP: https://www.notion.so/4055b2d1a55f80f0bbcbe3d62623cb54
- 작업 카드: https://www.notion.so/4055b2d1a55f805f8f66e16103399cef
- 원본 ZIP SHA-256: `dea0f3f3ec9350070494a2b32c728debad5fc06ec0c2e05be93f1c0d26b2f590`

## 저장과 조회

V30은 기존 `shelter.map_templates`에 `dog-meeting-courtyard / 1.0.0` 한 행을 추가한다. `auto_assign=false`, `manifest.purpose=dog_meeting`으로 구분하며 보호소 기본 맵·자동 연결·강아지·공개 설정은 수정하지 않는다. DB에는 manifest, 예제 layout, 36개 파일의 크기·유형·SHA-256을 저장한다. PNG와 좌표 JSON은 릴리스 JAR에 포함한다.

`GET /v1/meeting-scenes/dog-meeting-courtyard/versions/1.0.0`은 DB에 등록된 독대 에셋의 정보와 `manifestUrl`, `assetBaseUrl`, 해시를 반환한다. 없는 ID/버전 또는 보호소 탐색 맵은 404다. 앱은 API 원점에 `manifestUrl`을 붙여 읽고, manifest의 상대 경로를 `assetBaseUrl`에 붙인다.

예: `https://api.shelterconnect.win/v1/map-assets/1.0.0/dog-meeting-courtyard/spritesheet.png`

## 구성

- 512×256 투명 PNG 시트와 TexturePacker JSON Hash. 4배 버전 2048×1024도 포함.
- 지형 4종 32×32, 사물 23종 64×64. 개별 PNG와 `catalog.json`의 크기·앵커·contentBounds 제공.
- `example-layout.json`: 256×384 예제 배치. 실제 게임 충돌/이동 검증을 완료한 맵이 아니며 자동 타일 전환 세트도 아니다.
- `example-courtyard.png`: 스프라이트 재조립 예시.
- `background.png`: 승인된 1024×1792 정원 배경 시안. 좌표 단위가 예제 layout과 다르므로 혼용하지 않는다.
- 정적 에셋이며 강아지·대화 UI·애니메이션은 포함하지 않는다. nearest sampling을 사용한다.

서버 URL 규칙에 맞춰 개별 파일 이름의 `_`를 `-`, `spritesheet@4x`를 `spritesheet-4x`로 바꿨다. JSON 내 파일 참조도 함께 변경했고 프레임/스프라이트 ID는 유지한다. 원본 ZIP은 노션에 그대로 보관한다.

## 배포와 검증

기존 Ubuntu 자동 배포의 사전 백업·복제 DB 마이그레이션 리허설을 사용한다. DB 삭제/새 권한/운영 설정 변경은 필요 없다. 검사 항목은 기존 및 신규 보호소의 맵 3개 유지, 독대 에셋 분리 조회, 파일 해시·DB JSON 일치, 버전/경로 제한, 원본 픽셀 보존이다. 배포 후 공개 독대 조회 결과와 36개 파일을 다시 확인한다.
