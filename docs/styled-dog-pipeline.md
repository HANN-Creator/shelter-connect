# B-15 후속 · 사진과 승인 도트 스타일을 함께 사용하는 32px 생성

2026-10-05 사용자가 승인한 `minimax-2-style-matched` 방법을 백엔드 생성 도구로 고정했다. 실제 사진 없이 텍스트만 사용했을 때 다른 강아지가 만들어졌고, 사진의 성견 체형을 강하게 요구했을 때 그림체가 길고 각지게 바뀌었다. 이번 버전은 **사진 = 개체 특징, 고정 도트 = 그림체와 비율**로 역할을 분리한다.

노션: [B-15 사진 기반 도트·동작 에셋 생성](https://app.notion.com/p/3e35b2d1a55f803c9030e98a21d336e5). 선행 작업 B-04/B-05/B-10/B-12 및 승인된 로컬 비교 결과. 브랜치 `backend/b-15-approved-style-pipeline`.

## 저장된 규칙

- `backend/asset-styles/cozy32-v1/rules.json`: 버전, 기준 PNG SHA-256, 프롬프트, API 경로, 8개 행동·시간·반복 규칙.
- `backend/asset-styles/cozy32-v1/style.png`: 첫 번째 PixelLab 생성 강아지의 사용자가 선택한 native 32px 도트. 외형을 복사하는 대상이 아니라 그림체 참고 이미지다.
- 전신 사진과 **같은 사진의 검토된 얼굴 크롭**을 1024×512 콜라주로 만들어 `concept_image`에 전달한다. 얼굴 좌표·사진 해시·관찰 특징은 `traits.json`에 기록한다. 자동으로 얼굴을 추측하거나 다른 개체의 크롭을 재사용하지 않는다.
- `create-character-pro`, `method=create_from_concept`, `concept_image`와 `reference_image`를 모두 필수 입력. 작은 둥근 몸, 큰 머리, 짧고 읽기 쉬운 발, 1픽셀 외곽선과 단계형 명암 유지. 사진의 귀·털색·무늬만 변경한다.
- 앞/뒤/좌/우 기준 PNG를 직접 확인한 뒤 해시가 결합된 검토 기록을 남긴다. 네 방향에서 같은 개체인지, 그림체가 유지되는지, 발·귀·꼬리가 잘리지 않는지 확인한다. 파일 검증만으로 닮았다고 승인하지 않는다.
- `animate-pixminimax`: 방향마다 입력 프레임 + 생성 8프레임 = 9프레임. 기본 전체 계획은 8행동 × 4방향 = 32클립/288프레임. `--actions WALK`로 부분 생성도 가능하지만 전체 완료라고 표시하지 않는다.
- `IDLE, WALK, RUN, SNIFF, TAIL_WAG, BACK_OFF`는 첫/마지막 프레임 참조를 동일하게 전달. `SIT, LIE_DOWN`은 마지막 자세 유지, 프론트 복귀 시 역순 재생. 뒷모습에서는 눈·주둥이 등이 나오지 않도록 별도 제약.
- 결과는 native 32×32 RGBA. 블러, 강제 축소, 프레임별 재중앙 정렬 없음. PNG 시트는 원본 픽셀을 보존한다. GIF는 보기용. 고정 렌더 기준점 `(16,30)`, 실제 이동 속도는 프론트가 처리하며 BACK_OFF는 바라보는 방향의 반대 벡터를 제공한다.
- 앉기/눕기 끝부분에서 고개가 다른 방향으로 돌아가는 등 결함이 있으면, 시각 검토한 정상 마지막 자세를 유지할 수 있다. `frame-reviews.json`에 클립별 `holdFromFrame`, 원본 9장의 `rawFrameSha256`, 이유를 기록해야 하며 반복 동작에는 허용하지 않는다. 원본 파일은 보존하고, 시트의 `sourceFrameIndices`에 사용한 원본 번호를 기록한다. 픽셀을 그리거나 수정하지 않는다.
- 사진으로 확인되지 않는 뒷면은 추정이라고 기록. 움직임 라이브러리는 기능 예시이며 사진으로 실제 성격이나 행동을 추정한 것이 아니다.

## 실행

Pillow는 기존 `backend/motion-requirements.txt`를 사용한다. 인증은 환경변수 `PIXELLAB_API_KEY`, `DATA_GO_KR_SERVICE_KEY` 또는 무시되는 `.env` 파일에서만 읽는다. 값은 명령 인자·로그·저장소에 넣지 않는다.

```sh
cd backend
python3 scripts/generate_styled_dog.py source --output /absolute/review/run \
  --data-env-file .env.data-go-kr --exclude 426333202601081 428355202600524
# photo-1.png를 보고 같은 개체의 특징과 정규화한 얼굴 좌표를 traits.json에 작성
python3 scripts/generate_styled_dog.py prepare --output /absolute/review/run --traits /absolute/traits.json
python3 scripts/generate_styled_dog.py character --output /absolute/review/run --env-file .env.pixellab --allow-paid-calls
# 사진·스타일·4방향 PNG를 시각 검토한 후에만 실행
python3 scripts/generate_styled_dog.py review --output /absolute/review/run --review-note '확인한 귀·무늬·그림체·방향과 남은 한계를 구체적으로 기록'
python3 scripts/generate_styled_dog.py animate --output /absolute/review/run --env-file .env.pixellab --allow-paid-calls
python3 scripts/generate_styled_dog.py package --output /absolute/review/run
```

Gradle에서도 동일 도구를 호출한다. 예: `./gradlew generateStyledDog -PdogPipelineArgs='["--help"]'`. `ASSET_HARNESS_PYTHON`으로 Python 실행 파일을 지정할 수 있다. `--ca-file`을 생략하면 시스템의 검증된 CA를 사용하며 TLS 검증을 끄는 옵션은 없다.

`traits.json` 필수 필드: `animalId`, `sourcePhoto`(photo-1.png 또는 photo-2.png), `sourcePhotoSha256`, `photoReviewed: true`, `reviewNote`, `faceBox: [left, top, right, bottom]`(각 0~1), `identityDescription`(850자 이내), `motionDescription`, `rearDescription`(각 300자 이내), `seed`. 선택 필드 `unknownFeatures`에 보이지 않는 특징을 기록한다. 일반 동물 관찰 기록의 문자열을 프롬프트로 자동 복사하지 않는다.

## 비용·재개·공개 경계

각 POST는 요청 해시·endpoint·상태를 먼저 저장하고 반환된 job ID로 재개한다. 응답을 잃어 결과가 불명확하면 재요청하지 않는다. 실패한 작업도 자동 재생성하지 않는다. 정상 완료를 재실행하면 저장 결과를 재사용한다. 출력 폴더 잠금과 작업별 잠금으로 동시 중복을 차단한다. 새 요청에는 `--allow-paid-calls`가 필요하며 CI는 외부 생성 API를 호출하지 않는다.

기존 승인 샘플은 기준/회전 20 + WALK 4 = 24 generations였다. 전체 8행동은 동일 단가일 때 20 + 32 = 52지만, 실제 청구는 각 job의 `usage`를 합산한다. 계정 잔액 차이는 다른 동시 작업을 포함할 수 있으므로 청구 근거로 사용하지 않는다. 실패·재시도·단가 변경은 별도다.

공공 API URL은 인증키를 출력하지 않고 고정 서비스로만 조회한다. 사진은 정부 이미지 호스트만 허용하고 HTTPS·크기 제한·리다이렉트 차단을 적용한다. PixelLab ZIP은 정확한 회전 PNG만 선택해 읽는다. 재개 전에 사진·스타일·규칙·특징 해시를 검사한다.

패키지에는 허용된 사진·PNG·GIF·manifest·검토 기록만 포함한다. `raw/`, `plan/`, `.env`, 잔액·전체 응답·개인 경로는 공개 묶음에서 제외한다. `status=COMPLETED`는 32클립 파일 완성을 뜻하며 시각 검토/운영 승인과 별도다. `published=false`, `productionApproved=false`가 기본이다.

**적용 범위:** 백엔드 저장소의 명시 실행형 생성 파이프라인과 하네스이다. 기존 Spring 사진 등록 API의 64px 큐·Storage·DB·워커를 이번 실험 경로로 자동 교체하지 않는다. 운영 등록 흐름으로 전환하려면 방향별 manifest와 검토/공개 단계를 기존 작업 큐에 연결해야 한다. 여기서 만든 새 실제 강아지는 운영 DB에 등록하거나 공개 Storage에 올리지 않는다.

## 검증

`python3 -m unittest discover -s scripts -p test_styled_dog_pipeline.py`는 사진/스타일 역할, 같은 개체 크롭, 변조/낡은 승인 거절, 32개 요청의 방향/반복, 불명확한 유료 요청 재전송 금지, 완료/접수 작업 재개, PNG 좌표·반투명 픽셀 보존, 부분 완료와 비밀 파일 제외를 검증한다. 기존 CI의 scripts 테스트 검색에 포함된다.

실제 새 개체 생성 및 시각 검토 결과는 이 문서 후속 기록과 PR에 남긴다.

## 서버 저장·조회 연결

B-40에서 이 파이프라인을 기존 서버 작업 큐와 비공개 Storage·DB에 연결했다. [생성·검토·저장·프론트 조회 API](persistent-styled-assets.md)를 따른다. CLI는 기존 샘플 비교·명시 실행 도구로 유지한다.
