# 의존성 보안 검사

B-33부터 GitHub의 **Dependency security / Maven and Python vulnerabilities** 검사에서 서버의 Maven 라이브러리와 모션 Python 환경의 설치 패키지를 OSV에 조회한다.

- 백엔드 관련 PR과 main 변경 때 실행한다. 간접 의존성도 Gradle이 실제로 선택한 버전으로 검사한다.
- 매주 월요일 10:17 한국 시간에 main을 다시 검사한다. 코드가 같아도 새로 발표된 취약점을 확인하기 위해서다. GitHub 대기열에 따라 실행 시각은 늦어질 수 있다.
- Actions의 Run workflow로 수동 실행할 수도 있다.
- GitHub 작업 요약에 검사 시각·상태·패키지 수·문제 패키지와 advisory ID를 남긴다. 기존 단위·DB·배포 이미지 검사는 별도로 실행한다.

## 결과 해석

| 상태 | 종료 코드 | 다음 작업 |
| --- | --- | --- |
| clean | 0 | 조회한 버전에 현재 알려진 일치 항목이 없음 |
| vulnerable | 1 | 표시된 취약점과 영향 범위를 확인하고 별도 브랜치에서 패치·회귀 검사 |
| error | 2 | 목록 생성, 네트워크, 응답 형식 등 검사 실패를 해결하고 다시 실행 |

조회 실패를 clean으로 처리하거나 자동으로 무시하지 않는다. OSV 요청은 최대 3회 재시도하며, 결과 개수 불일치·잘못된 응답·끝나지 않은 페이지 이동도 실패한다. 취약점이 있다고 자동으로 라이브러리를 올리거나 서버를 배포하지 않는다.

PR 병합 전 이 검사와 기존 Backend 검사가 모두 성공해야 한다. 현재는 작업 규칙으로 적용하며 GitHub 저장소 ruleset의 필수 검사 지정은 이 작업에서 바꾸지 않는다.

## 로컬 실행

backend 폴더에서 실행한다. 모션 패키지는 별도 가상 환경에 설치한다.

```sh
./gradlew exportRuntimeDependencies
python3 -m venv .venv-security
.venv-security/bin/pip install -r motion-requirements.txt
.venv-security/bin/pip list --format=json > build/reports/security/python.json
python3 -m unittest discover -s scripts -p 'test_dependency_security.py'
python3 scripts/check_dependency_security.py \
  --maven build/reports/security/maven.json \
  --python build/reports/security/python.json
```

`build/reports/security/result.json`을 보면 된다. Python 목록이 requirements의 고정 버전과 다르면 검사에 실패한다. OSV로 보내는 값은 공개 패키지 이름·생태계·버전이다. 서비스 키·DB 연결 정보는 필요하지 않다.

## 검사 범위

애플리케이션 Maven 런타임의 직접·간접 라이브러리와 CI에 설치한 Python 환경을 검사한다. 알려진 취약점과의 버전 대조이며, 애플리케이션 권한 검토나 새 취약점 탐지까지 보장하지 않는다. Docker OS 패키지·JDK·네이티브 확장 전체의 취약점 검사는 별도 범위다. pip도 26.2.1로 고정해 CI와 배포 이미지에 함께 설치한다. 기본 OS가 제공하는 다른 설치 도구·시스템 Python 패키지는 이 애플리케이션 목록과 별도다.

추가 패치: 기존 로컬 pip 24.3.1에서 알려진 취약점이 발견되어 [공식 PyPI의 pip 26.2.1](https://pypi.org/project/pip/26.2.1/)로 고정했다.

공식 기준: [OSV batch API와 페이지 이동](https://google.github.io/osv.dev/post-v1-querybatch/), [GitHub 예약 워크플로](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).
