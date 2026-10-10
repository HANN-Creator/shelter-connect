# B-83 배포 빌드 다운로드 제한 대응

[노션 카드](https://app.notion.com/p/3f55b2d1a55f807e98daf0d1fd1b98e9) · `backend/b-83-deployment-build`

## 확인한 원인

2026-10-10 Render `dep-db50ao3bc2fs73ds125g`는 프로젝트 구성 단계에서 Maven Central의 의존성 POM 요청에 HTTP 429를 받아 실패했다. Spring Boot buildpack/loader, Commons Compress, Spring Core 다운로드가 차단됐다. 사용자 제공 상세 로그로 확인했으며 컴파일이나 서버 실행에 도달하지 않았다.

기존 `main` bb9bc39의 동일 파일 트리는 GitHub CI와 로컬 Linux/amd64 Docker 빌드를 통과했다. `.dockerignore`를 보고 의심했던 품질 규칙 누락은 실제 Docker JAR 검사에서 재현되지 않았다. `quality-rules.json`, 스타일, 꼬리 검수 참고 PNG/JSON 모두 포함됐다. 이 건은 누락 수정이 아니다.

## 바뀌는 동작

- Gradle 플러그인과 서버 컴파일·런타임 의존성을 소스 복사 전 별도 Docker 레이어에서 받는다. 소스/품질 규칙만 바뀌면 이 레이어를 재사용한다. 테스트 의존성을 불필요하게 받지 않는다.
- 소스 컴파일과 `bootJar`는 `--offline`으로 실행한다. 필요한 의존성이 빠졌다면 실패하며, 불완전한 JAR로 성공 처리하지 않는다.
- 다운로드 단계의 GET/HEAD 429에만 60초, 180초 대기 후 재시도한다. 최초 요청을 포함해 최대 3회이며 기존 다운로드를 유지한다. 컴파일 오류, 400/401/403/404, 다른 실패는 즉시 종료한다. 원래 종료 코드와 진단 로그를 보존한다.
- CI는 최종 컨테이너 안의 JAR에서 공유 규칙과 참조 이미지를 읽고, 체크아웃의 품질 규칙 SHA와 비교한다. 실제 Python 요청 조립기에서 걷기/쉬기 payload를 구성하고 기존 모션 렌더러도 실행한다. 외부 AI 호출은 없다.

캐시가 없는 첫 배포나 의존성 변경 때는 여전히 Maven Central 접근이 필요하다. 제한이 오래 지속되면 유한 재시도 후 실패한다. 자동 재시도를 무한히 늘리거나 캐시를 지우는 해결책이 아니다. [Maven Central 안내](https://central.sonatype.org/faq/429-error/)는 불필요한 재다운로드를 줄이고 캐시를 사용하는 것을 권장한다. [Docker 캐시 문서](https://docs.docker.com/build/cache/optimize/)의 의존성/소스 레이어 분리 방식을 따른다.

## 검증과 적용 경계

재시도 스크립트는 가짜 Gradle/대기 명령으로 429 후 성공, 지속 429의 횟수 상한·실패 상태, 일반 실패 즉시 종료를 검사한다. 실제 Linux Docker 이미지 빌드와 배포 이미지 내 검증 결과는 PR·노션에 기록한다. 소스 테스트 결과만 배포 성공으로 간주하지 않는다.

API·DB·런타임 환경변수 변경은 없다. 기존 품질 규칙 v36과 해시 `14baf16dd602c4316676b3bc6f33cfcac6e48a86fbb4a73177be35334ec26002`를 유지한다. PR 병합과 Render 배포는 별개이며, 실제 배포 SHA 확인 전 새 버전이 실행된다고 보고하지 않는다. 기존 강아지 생성 작업의 승인과 앱 재생 검증은 배포 후 이어간다.
