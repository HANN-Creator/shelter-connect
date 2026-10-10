# B-83 / B-84 배포 빌드 다운로드 제한 대응

[노션 카드](https://app.notion.com/p/3f55b2d1a55f807e98daf0d1fd1b98e9) · `backend/b-83-deployment-build`

## B-84: 최초 배포부터 오프라인 빌드

[B-84 노션](https://app.notion.com/p/3f55b2d1a55f80e292b9fcc5672acab2) · `backend/b-84-offline-deployment-cache`

후속 Render `dep-db510q5ckfvc738ir1bg`는 실제 `abc5a6f`를 체크아웃했고 60초·180초 대기 후에도 세 번 모두 같은 플러그인 POM 요청에 429를 받았다. 컴파일 전 실패다. 첫 다운로드가 성공하지 않아 B-83 캐시 레이어를 만들지 못했다. 아래 B-83의 유한 재시도만으로는 이 상황을 해결하지 못했다.

이제 `Dockerfile`은 검증한 의존성 묶음을 이 저장소의 GitHub Release에서 받는다. 원래 Maven Central/Gradle Plugin Portal에서 받은 동일 파일을 재사용하며 미러나 라이브러리 버전은 바꾸지 않는다. Docker가 고정 SHA256을 확인한 뒤 압축을 풀고, 내부 `inputs.sha256`으로 Gradle 버전·Wrapper·플러그인·의존성 설정을 확인한다. Gradle 실행은 `--offline`과 `RUN --network=none`을 함께 사용한다. Render 빌드 캐시가 없어도 Maven Central 접근이 발생하지 않는다.

- 소스·품질 규칙만 바꾸면 기존 묶음을 재사용한다. 현재 묶음은 `abc5a6f`의 빌드 입력용이며 모든 설정 파일 해시가 일치해야 한다.
- `build.gradle`, `settings.gradle`, Wrapper를 바꾸면 새 묶음과 URL/SHA 핀을 같은 PR에 갱신한다. 오래된 묶음을 쓰면 입력 해시 검사에서 실패한다. 온라인 다운로드로 조용히 전환하지 않는다.
- 묶음에는 격리된 Linux builder의 `caches/modules-2`, `wrapper/dists`, 입력 해시만 들어간다. 소스·컴파일 결과·사용자 홈·환경변수·인증·init script·daemon log·lock·GC 파일은 제외한다. 최종 실행 이미지에는 캐시가 들어가지 않는다.
- Python 설치·기본 이미지·GitHub Release 다운로드에는 여전히 네트워크가 필요하다. 이 변경은 확인된 Gradle 429 경로를 제거하며 모든 공급자 장애를 해결한다고 주장하지 않는다.
- 실제 Render 배포와 강아지 자동 승인·앱 재생은 별도로 확인한다. 서비스 설정/환경변수/DB/API/품질 규칙 변경은 없다.

### 의존성을 바꿀 때 갱신

백엔드 루트에서 다음 명령으로 격리된 builder의 의존성 캐시만 내보낸다. 전체 개인 `~/.gradle`을 올리지 않는다. 온라인 접근은 이 갱신 단계에서만 필요하며 B-83의 유한 429 재시도를 유지한다.

```sh
docker buildx build --platform linux/amd64 -f Dockerfile.dependencies \
  --output type=local,dest=/tmp/shelter-gradle-cache .
```

생성된 `gradle-cache.tar.gz`, 체크섬, `inputs.sha256`을 검토한다. 새 고유 태그의 GitHub 사전 릴리스에 올리고 기존 릴리스는 덮어쓰지 않는다. `Dockerfile`의 Release URL과 `ADD --checksum`을 함께 갱신한다. 새 묶음의 첫 빌드는 `docker build --no-cache`로 실행하고, 캐시 복원부터 Gradle 실행까지 네트워크가 없는 상태에서 성공하는지 확인한다. CI의 전체 테스트·보안 검사와 512MB 실제 이미지 검증을 통과한 같은 HEAD만 병합한다.

[Gradle의 캐시 복사 지침](https://docs.gradle.org/current/userguide/dependency_caching.html#sec:copying_dependency_cache)에 따라 메타데이터와 파일을 함께 보존하고 lock/GC 파일을 제외한다. [Docker ADD checksum](https://docs.docker.com/reference/dockerfile/#add---checksum)으로 다운로드 내용을 고정한다.

## B-83 기록: 확인한 원인

2026-10-10 Render `dep-db50ao3bc2fs73ds125g`는 프로젝트 구성 단계에서 Maven Central의 의존성 POM 요청에 HTTP 429를 받아 실패했다. Spring Boot buildpack/loader, Commons Compress, Spring Core 다운로드가 차단됐다. 사용자 제공 상세 로그로 확인했으며 컴파일이나 서버 실행에 도달하지 않았다.

기존 `main` bb9bc39의 동일 파일 트리는 GitHub CI와 로컬 Linux/amd64 Docker 빌드를 통과했다. `.dockerignore`를 보고 의심했던 품질 규칙 누락은 실제 Docker JAR 검사에서 재현되지 않았다. `quality-rules.json`, 스타일, 꼬리 검수 참고 PNG/JSON 모두 포함됐다. 이 건은 누락 수정이 아니다.

### B-83에서 바꾼 동작

- Gradle 플러그인과 서버 컴파일·런타임 의존성을 소스 복사 전 별도 Docker 레이어에서 받는다. 소스/품질 규칙만 바뀌면 이 레이어를 재사용한다. 테스트 의존성을 불필요하게 받지 않는다.
- 소스 컴파일과 `bootJar`는 `--offline`으로 실행한다. 필요한 의존성이 빠졌다면 실패하며, 불완전한 JAR로 성공 처리하지 않는다.
- 다운로드 단계의 GET/HEAD 429에만 60초, 180초 대기 후 재시도한다. 최초 요청을 포함해 최대 3회이며 기존 다운로드를 유지한다. 컴파일 오류, 400/401/403/404, 다른 실패는 즉시 종료한다. 원래 종료 코드와 진단 로그를 보존한다.
- CI는 최종 컨테이너 안의 JAR에서 공유 규칙과 참조 이미지를 읽고, 체크아웃의 품질 규칙 SHA와 비교한다. 실제 Python 요청 조립기에서 걷기/쉬기 payload를 구성하고 기존 모션 렌더러도 실행한다. 외부 AI 호출은 없다.

캐시가 없는 첫 배포나 의존성 변경 때는 여전히 Maven Central 접근이 필요하다. 제한이 오래 지속되면 유한 재시도 후 실패한다. 자동 재시도를 무한히 늘리거나 캐시를 지우는 해결책이 아니다. [Maven Central 안내](https://central.sonatype.org/faq/429-error/)는 불필요한 재다운로드를 줄이고 캐시를 사용하는 것을 권장한다. [Docker 캐시 문서](https://docs.docker.com/build/cache/optimize/)의 의존성/소스 레이어 분리 방식을 따른다.

### B-83 검증과 적용 경계

재시도 스크립트는 가짜 Gradle/대기 명령으로 429 후 성공, 지속 429의 횟수 상한·실패 상태, 일반 실패 즉시 종료를 검사한다. 실제 Linux Docker 이미지 빌드와 배포 이미지 내 검증 결과는 PR·노션에 기록한다. 소스 테스트 결과만 배포 성공으로 간주하지 않는다.

API·DB·런타임 환경변수 변경은 없다. 기존 품질 규칙 v36과 해시 `14baf16dd602c4316676b3bc6f33cfcac6e48a86fbb4a73177be35334ec26002`를 유지한다. PR 병합과 Render 배포는 별개이며, 실제 배포 SHA 확인 전 새 버전이 실행된다고 보고하지 않는다. 기존 강아지 생성 작업의 승인과 앱 재생 검증은 배포 후 이어간다.
