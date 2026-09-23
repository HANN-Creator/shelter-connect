# Swagger API 명세서

개발 서버: **https://shelter-connect-dev.onrender.com**

- [Swagger UI](https://shelter-connect-dev.onrender.com/swagger-ui/index.html)
- [OpenAPI JSON](https://shelter-connect-dev.onrender.com/v3/api-docs)
- [OpenAPI YAML](https://shelter-connect-dev.onrender.com/v3/api-docs.yaml)
- [서버 상태](https://shelter-connect-dev.onrender.com/actuator/health/readiness)

실제 배포 결과는 [B-20 카드](https://app.notion.com/p/Swagger-API-3e45b2d1a55f80c28871d93557dfb002)와 PR에서 확인해줘. Render Free가 쉬고 있으면 첫 화면이 늦게 열릴 수 있어.

## 처음 호출해보기

1. 공개 조회에서 `GET /v1/shelters`를 열고 **Try it out → Execute**를 누르면 돼. 응답의 보호소 ID로 강아지 목록을 이어서 확인할 수 있어.
2. 로그인 기능을 시험할 때는 Supabase Auth에서 로그인한 뒤 사용자 `access_token`을 복사해 **Authorize**에 넣어. `Bearer `는 붙이지 않아도 돼. 서버용 secret/service-role 키를 넣는 칸은 아니야.
3. 처음 로그인한 계정은 `POST /v1/me`로 우리 서버에 연결해. 보호소 관리 요청은 해당 승인 보호소의 활성 소속이어야 하고, 운영 요청은 OPERATOR 권한이 필요해.
4. 파일을 올릴 때는 사진 업로드 API의 `file`을 선택하고 `metadata` JSON을 채워. metadata는 application/json 파트로 전송돼. 사용 허가를 확인한 출처 ID가 필요해.

화면의 예시는 가상 ID이므로 조회해서 받은 실제 개발 데이터 ID로 바꿔야 해. `Try it out`은 현재 접속한 서버로 실제 요청을 보내. 저장 요청은 데이터를 바꾸고 AI·생성 요청은 기능이 켜져 있을 때 사용량이 발생해. 토큰은 문서의 브라우저 저장소에 보관하지 않으며 페이지를 새로 열면 다시 입력하면 돼.

## 사진·행동 연결할 때

[사진·특징 입력 명세서](asset-input-workflow.md)에 요청 순서가 있어. 관찰 확인 → AI 초안 생성 → 행동 확인 → 사진 등록 → 체형 확인 → 동작 승인 순서야. AI 초안 요청은 HTTP 200이어도 `status`를 확인해줘. 생성 작업은 202 응답 뒤 작업 조회 API로 이어가면 돼.

앱은 승인된 에셋과 공개 행동 설정을 함께 읽어. [행동별 재생 명세서](dog-action-playback-spec.md)에 행동 코드, 없는 행동 처리, 공놀이 연결이 정리돼 있어. Swagger가 RN 애니메이션까지 실행해주지는 않아.

## 서버 쪽 구성

Spring Boot 4.1.1에 [springdoc-openapi 3.1.1](https://springdoc.org/getting-started.html)을 연결했어. 런타임 컨트롤러에서 경로와 응답 DTO를 읽고 `api-documentation.json`에서 엄격한 JSON 요청의 필드·예시와 동적 응답을 보충해. API가 추가되면 문서 등록도 함께 해야 하고, 누락되면 테스트에서 확인할 수 있어.

로컬에서도 `/swagger-ui/index.html`, `/v3/api-docs` 주소가 같아. 서버 주소는 상대 경로 `/`를 사용하므로 로컬 화면에서 개발 서버로 토큰이 넘어가지 않아. 문서 화면은 로그인 없이 볼 수 있고 실제 관리 API의 인증·소속 검사는 그대로야. `API_DOCS_ENABLED=false`로 문서 UI와 OpenAPI 제공을 함께 끌 수 있어.

검증은 런타임 46개 API와 문서의 일치, 스키마 참조, multipart 설정, 서로 다른 작업 응답 모델, 공개 문서 접근과 기존 보호 API의 401을 확인해. 실제 배포 후 공개 조회와 인증 차단도 다시 확인해.
