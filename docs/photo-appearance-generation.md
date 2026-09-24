# 사진 외형 분석과 얼굴 참고 이미지

B-25 · [노션 카드](https://app.notion.com/p/3e45b2d1a55f801fa91ff588e52b3694)

사진 등록 뒤 서버가 Luna로 보이는 외형과 얼굴 영역을 분석한다. 원본 전체와 얼굴을 잘라낸 사진, 구체적인 외형 설명을 PixelLab에 함께 전달한다. 처음 두부를 만들 때 준비했던 입력 구성을 서버에서도 만들기 위한 변경이다.

## 처리 순서

1. 기존 사진 등록 API로 허가된 사진을 받는다. 워커가 등록된 비공개 사진을 읽어 최대 1024px로 정리하고 메타데이터를 제거한다.
2. 기존 `OPENAI_MODEL`의 Luna에 사진을 전달한다. 털색·무늬·귀·눈·주둥이·코·꼬리·체형과 얼굴 영역을 구조화된 JSON으로 받는다. 성격·건강·품종은 추정하지 않으며, 사진 속 문구를 지시로 사용하지 않는다.
3. 강아지 한 마리와 보이는 얼굴인지, 좌표·설명 길이가 유효한지 검사한다. 얼굴 영역에는 가로·세로 각각 12% 여백을 더하고 원본 경계 안에서 잘라낸다. 귀와 코를 포함하는 영역이며 이미지를 늘이거나 얼굴을 새로 그리지 않는다.
4. 분석 결과·모델·입력 해시·시도 횟수를 해당 BASE 단계의 비공개 `result.preparation`에 저장한다. PixelLab에 전체 사진과 얼굴 사진을 별도 `reference_images`로 보내고, `cozy-dog-v1`은 그림체 참고용으로 유지한다. 사진의 외형 설명은 생성 프롬프트에 포함한다.
5. 생성된 기본 도트는 기존처럼 `RIG_REVIEW`에서 체형 확인을 기다린다. 이후 동작 생성과 최종 검토·공개 절차는 같다.

Luna의 얼굴 좌표는 근사치다. 규격 검사를 통과해도 얼굴 영역과 외형이 정확하다는 보장은 없으므로 기본 도트 검토는 계속 필요하다. 아직 얼굴 좌표를 직접 편집하는 프론트 화면은 없다.

## API와 실패 처리

사진 등록 요청 형식은 바뀌지 않는다. 보호소가 사진 한 장을 보내면 서버가 참고 사진 두 장을 준비한다. 사진을 OpenAI에서 분석하고 PixelLab에서 가공하는 사용 범위를 확인한 자료만 등록한다.

인증된 관리용 작업 조회의 BASE 단계에 `result.preparation`이 추가된다. `status`, `version`, `model`, `attempts`, `startedAt`, `completedAt`, `photoSha256`, `analysis`를 확인할 수 있다. 공개 에셋 manifest에는 분석·원본 사진·얼굴 사진을 노출하지 않는다.

- `PHOTO_APPEARANCE_REQUIRES_REVIEW`: 여러 강아지, 얼굴 미확인, 잘못된 좌표·설명 등. PixelLab을 호출하기 전에 `FAILED`로 멈춘다. 사진을 확인하고 필요하면 새 사진을 등록한다.
- `APPEARANCE_AI_*`: Luna 호출 오류·거절·응답 미완료. 자동 반복 없이 `FAILED`로 멈춘다.
- `APPEARANCE_INTERRUPTED`: 분석 시작 기록은 있으나 완료 기록이 없는 채 워커가 재시작됐다. 자동으로 다시 호출하지 않는다.
- `PHOTO_APPEARANCE_SOURCE_CHANGED`: 저장한 분석과 입력 사진 또는 분석 버전이 다르다. 해당 분석을 다른 사진에 재사용하지 않는다.

운영자의 기존 `POST /v1/operations/asset-jobs/{id}/retry`로 명시적으로 재시도한다. 완료된 분석은 PixelLab 오류나 생성 한도 대기 뒤에도 재사용한다. 미완료 분석만 다시 요청할 수 있으며 호출 시도 횟수를 남긴다. PixelLab의 접수 여부 불명 처리와 `reconcile`은 기존 규칙을 유지한다.

## 설정과 적용 범위

새 키나 환경변수는 없다. `AI_ENABLED`, `OPENAI_API_KEY`, `OPENAI_MODEL`과 기존 PixelLab 설정을 사용한다. 현재 구성 모델은 이미지 입력을 지원하는 `gpt-5.6-luna`다. BASE를 새로 준비할 때 Luna 이미지 분석 1회가 추가된다. 저장된 BASE와 승인된 에셋은 다시 생성하지 않는다.

기존 JSON 결과 컬럼을 사용하므로 DB 마이그레이션은 없다. 분석 시작 기록을 유료 호출 전에 저장하며, 네트워크 요청 중 DB 트랜잭션은 열어두지 않는다. 저장·전송 전에 사진 허가와 작업 소유권을 재확인한다.

공식 문서: [Luna 입력 지원](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [이미지 분석](https://developers.openai.com/api/docs/guides/images-vision), [구조화된 출력](https://developers.openai.com/api/docs/guides/structured-outputs).

## 검증

Java 테스트 294개, PostgreSQL 통합 검사 233개, Python 도구 검사 20개와 빌드를 통과했다. 사진 두 장의 실제 전송, 얼굴 좌표 검사, 외형 설명 길이, 분석 재사용, 중단 후 자동 재호출 차단, 분석 도중 허가 철회, 공개 manifest에서 분석 제외를 확인했다.

`backend/scripts/check_photo_appearance_live.py`는 실제 개발 서버의 사진 등록부터 BASE까지 확인하는 도구다. `--photo`, `--project-ref`, `--server-commit`, `--env-file`, `--storage-env-file`, `--output-dir`와 `--allow-paid-calls`를 명시한다. 사용 허가를 확인한 1024px 이하 PNG로 실행하며 Luna 분석 1회와 PixelLab BASE 1회를 확인한다. 중복 업로드는 같은 작업을 반환해야 한다. 체형 확정·동작 생성·공개 승인은 하지 않고 임시 계정·허가·등록만 정리한다. `appearance.json`, `base.png`, 얼굴 영역 확인 이미지와 비밀 값 없는 `report.json`을 남긴다.

얼굴 확인 이미지는 서버에 저장된 분석 좌표로 재구성한 것이다. 생성된 도트는 서버의 인증된 API에서 직접 받은 원본이다. 실제 실행 결과와 디자인 차이는 병합 기록·노션에 추가한다.

이후 동작 생성과 검토·앱 조회까지 이어서 확인하는 도구는 [B-26 사진·동작 전체 검증](photo-motion-verification.md)에 정리했다. 기본 도트 검토만 필요한 경우에는 위 B-25 도구를 그대로 쓴다.
