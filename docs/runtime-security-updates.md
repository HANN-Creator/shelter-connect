# 서버 라이브러리 보안 업데이트 · B-30

2026-09-29 점검에서 확인한 런타임 의존성을 업데이트한다.

| 라이브러리 | 이전 | 적용 | 범위 |
| --- | --- | --- | --- |
| Tomcat | 11.0.24 | 11.0.26 | HTTP 서버·WebSocket·EL을 같은 패치 버전으로 맞춤 |
| Pillow | 11.3.0 | 12.3.0 | 서버의 사진·스프라이트 처리 환경 |

Spring Boot 4.1.1의 기본 Tomcat 버전 대신 패치 버전을 명시했다. 다음 Boot 업데이트 때는 관리 버전을 확인한 후 이 고정값을 제거하거나 함께 올린다. 모션 하네스의 팔레트·외곽선·프레임 계산은 바꾸지 않는다.

검증은 Java 단위/HTTP, PostgreSQL 통합, Python 설정 및 모션 회귀 검사, 배포 컨테이너의 512MB 실행으로 한다. 라이브러리 버전 변경만으로 배포 완료를 판단하지 않고 실제 Render 배포 SHA도 확인한다.

- [Apache Tomcat 공식 보안 공지](https://tomcat.apache.org/security-11.html)
- [Pillow 12.3.0 릴리스](https://pillow.readthedocs.io/en/stable/releasenotes/12.3.0.html)
- [노션 B-30](https://app.notion.com/p/3e95b2d1a55f808083b6d20db05cf975)
