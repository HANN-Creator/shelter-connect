# WIN-03 GitHub → Windows 미니PC 자동 배포

main의 Backend CI가 성공하면 Mini PC release가 **동일한 테스트 커밋**을 체크아웃하고 JAR·Python 스크립트·에셋 기준을 묶는다. 배포기는 같은 main 커밋의 Dependency security 성공까지 확인한다. main의 두 검사는 경로 필터 없이 실행해 문서 병합으로 HEAD가 이동해도 검증된 배포를 놓치지 않는다. GitHub-hosted runner에서 빌드하며 운영 PC에는 GitHub self-hosted runner를 설치하지 않는다. DB·Auth·Storage 개인키는 GitHub에 업로드하지 않는다.

미니PC는 Actions/Contents 읽기 전용 토큰을 DPAPI 및 ACL로 보호하고 2분마다 성공한 main 빌드만 가져온다. 토큰은 api.github.com 요청에만 사용하며 서명된 외부 아티팩트 다운로드에는 전달하지 않는다. GitHub 아티팩트 SHA256, 파일별 SHA256, CI의 저장소·이벤트·분기·커밋·결과, 운영 저장소 클래스, Windows 작업 잠금 코드를 검사한다.

선행 변경 WIN-01 Windows 호환 및 WIN-02 네이티브 Storage가 먼저 병합되어야 패키징이 성공한다. 기존 Supabase 전용 main JAR는 배포되지 않는다. 프론트 코드는 이 작업에 포함하지 않는다. 노션 외부 카드는 도구 미제공으로 미동기화다.

## 운영 절차

1. 배포 대상 PR을 검토하고 main에 병합한다. 저장소에 main을 쓸 수 있는 개발자는 운영 코드를 배포할 수 있으므로 기존 PR 검토 절차를 따른다.
2. Backend CI → Mini PC release 성공 후 다음 미니PC 확인 주기에 새 코드가 적용된다. 빌드 시간 외 약 2분이 추가된다.
3. 교체 전 전체 운영 백업을 생성한다. DB와 영구 파일은 교체 대상에서 제외한다. 터널·백엔드를 중지하고 앱 디렉터리만 바꾼다. 로컬/공개 readiness와 각 사진 버킷의 서명 다운로드를 확인한다.
4. 실패하면 이전 앱 디렉터리로 되돌려 **동일한 운영 DB·사진**을 사용한다. 실패한 커밋은 자동 재시도하지 않는다. C:\ProgramData\ShelterConnectDeploy\last-status.json 및 기존 서비스 로그를 검토한 뒤 수정 커밋을 병합한다.

DB 마이그레이션 목록/내용 또는 Python 의존성이 바뀌면 자동 배포는 중지한다. 먼저 별도 복제 DB/환경에서 검증하고 운영 마이그레이션·의존성 적용을 수행한 후 다시 배포한다. 서비스의 DB_MIGRATE=false, 기존 AI/작업자 설정은 자동 변경하지 않는다. 이전 코드가 새 스키마와 호환되는지도 검토한다.

## 이 PC의 설치

로컬 관리자 전용 outputs/Setup-GitHub-Deploy.py가 보호 설정과 ShelterGitHubDeploy(S4U/최고 권한, 2분 주기) 작업을 등록한다. 초기 enabled=false이며 실제 CI와 배포 전용 dry run을 통과하기 전에는 운영 교체하지 않는다. 실행 코드는 C:\ProgramData\ShelterConnectDeploy\bin에 고정 복사하며 아티팩트가 배포 도구 자체를 교체하지 않는다.

정지: 관리자 PowerShell `Disable-ScheduledTask -TaskName ShelterGitHubDeploy`. 재개 전에 실패 사유와 마지막 커밋을 확인한다. 토큰 만료 시 새 읽기 전용 토큰을 로컬 보호 설정에 입력하고 --check-only로 검사한다. GitHub 비밀번호·토큰을 채팅에 붙이지 않는다.

각 배포의 이전 app와 보고서는 C:\ProgramData\ShelterConnectPreview\releases에 남는다. 자동 삭제하지 않으므로 디스크 사용량을 점검하고 충분한 복구 백업과 현재/이전 정상 버전을 보존한 뒤 수동 정리한다. 운영 API가 잠시 중단되는 단일 PC 배포이며 무중단 배포는 아니다.

참고: GitHub 공식 workflow_run 이벤트와 Actions 아티팩트 REST API를 사용한다. https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run / https://docs.github.com/en/rest/actions/artifacts
