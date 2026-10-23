# B-107A Pillow 보안 업데이트

B-107 공개 에셋 응답 수정의 배포 전 검사에서 기존 Pillow 12.3.0에 OSV 보안 공지 39건이 확인됐다(2026-10-23). 별도 선행 수정으로 이미지 처리 의존성을 13.0.0에 고정한다. 노션 도구 미제공으로 외부 카드는 미동기화다.

- 근거: [Pillow 공식 13.0.0 보안 릴리스](https://pillow.readthedocs.io/en/stable/releasenotes/13.0.0.html), [영향 버전과 수정 버전이 명시된 공지](https://github.com/advisories/GHSA-22hg-r758-rrw9).
- 완료 기준: 현재 보안 검사 통과, 기존 이미지·모션 테스트 유지, 미니 PC의 실제 Python 설치 버전 및 배포 확인.
- 로컬 검증: 격리된 Pillow 13.0.0 환경에서 scripts 테스트 117개, motion-tests 26개 통과. OSV의 정확한 13.0.0 조회 결과 해당 공지 없음.
- 저장된 도트·시트와 DB에는 변경이 없으며 생성 API를 호출하지 않는다. 새로운 이미지 처리에서 수정 라이브러리를 사용한다.

## 미니 PC 적용 조건

기존 배포기는 설치된 Python 버전과 현재·후보 requirements의 정확한 pin을 비교한다. 이 검사를 우회하지 않는다. Windows 운영 Python의 Pillow도 별도 업데이트한 뒤 현재 앱의 requirements 기록을 같은 버전으로 맞춰야 한다.

운영 적용 전 실제 `ASSET_HARNESS_PYTHON` 경로와 배포 Python이 같은 환경인지 확인한다. 패키지 업데이트·현재 앱 requirements 기록·서비스 재시작은 미니 PC에서 수행해야 하며, GitHub PR 통과만으로 운영 업데이트가 끝났다고 기록하지 않는다. 적용 결과와 CI·병합 SHA는 PR에 기록한다.
