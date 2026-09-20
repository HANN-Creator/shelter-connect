# Supabase 개발 DB 적용 기록

2026.09.21, 사용자가 지정한 **shelter-connect-dev** 프로젝트에 V1 구조와 B-02 샘플을 넣었어. 적용 전에는 `shelter` 스키마와 앱 테이블이 없는 PostgreSQL 17.6 프로젝트였어.

로그인된 SQL 편집기에서 내보낸 초기화 SQL을 실행했고, 별도 조회로 아래 결과를 확인했어. DB 비밀번호나 API 키를 가져와 저장하지 않았어.

| 확인 항목 | 실제 결과 |
| --- | --- |
| 가상 보호소 | 2곳 |
| 강아지 | 5마리 |
| 관찰 기록 | 25개, 그중 확인 완료 20개 |
| 온기 보호소 | 봄이·두부·콩이 |
| 다온 보호소 | 밤이·해리 |
| 공개 상태 | 4마리 공개, 해리는 PAUSED·비공개 |
| RLS | 앱 테이블 11개와 Flyway 이력 모두 활성화 |
| anon / authenticated | shelter 스키마 접근 불가, 테이블 조회 권한 0개 |
| V1 이력 | 성공, 체크섬 2097257438 |

Supabase **Table Editor → schema 선택 → shelter → dogs**에서 다섯 강아지를 볼 수 있어. 기본으로 선택되는 `public` 스키마에는 앱 테이블이 없어. 보호소는 `shelters`, 관찰 내용은 `dog_observations`에 있어.

적용한 원본은 [V1](../backend/src/main/resources/db/migration/V1__create_shelter_domain.sql)과 [샘플 JSON](../backend/sample-data/dataset.json)이야. 초기화 SQL은 [내보내기 명령](../backend/sample-data/README.md#새-supabase-프로젝트에-처음-넣을-때)으로 만들었고, RLS를 함께 활성화하는 대시보드 옵션으로 실행했어. 구조·샘플·V1 이력은 같은 트랜잭션에 넣었어.

CI에서는 별도 임시 PostgreSQL에 생성 SQL을 넣고 실제 Flyway 검증과 재실행을 확인해. 앱 테이블뿐 아니라 이력 테이블의 RLS도 켠 상태로 검사해. 이 프로젝트에는 초기화 SQL을 다시 실행하지 말고, 다음 구조 변경부터 새 Flyway 버전을 추가하면 돼.

현재 확인한 것은 실제 DB 저장까지야. Spring Boot 원격 JDBC 연결, 서버 배포, RN 화면·API·AI 연결은 아직 하지 않았어. 기존 프론트 시안도 자동으로 이 DB를 읽지는 않아.
