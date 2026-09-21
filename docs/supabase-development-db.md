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

## V2·V3 적용 완료 · 2026.09.21

사용자 요청으로 같은 `shelter-connect-dev` 프로젝트(`gwimdiwrqfcqulefshoz`)에 V2와 V3를 적용했어. 대시보드의 main에는 PRODUCTION 표시가 있지만, 이번 대상은 사용자가 지정한 개발 프로젝트야. 다른 프로젝트는 변경하지 않았어.

적용 직전 V1 이력·체크섬과 데이터 건수를 확인했어. `exportSupabaseUpgrade`로 만든 SQL을 로그인된 SQL 편집기에서 실행했고, 원본 V2/V3와 Flyway 이력을 한 트랜잭션에 넣었어. 기존 11개 업무 테이블의 건수와 내용 지문을 비교한 뒤 일치할 때만 커밋했어. 추가되는 컬럼은 비교에서 제외했어.

| 확인 항목 | 실제 결과 |
| --- | --- |
| V1 | 성공 · 체크섬 `2097257438` |
| V2 | 성공 · 체크섬 `1587014355` |
| V3 | 성공 · 체크섬 `1820963059` |
| V2 컬럼 | generation_token, generation_expires_at, generation_attempts, generation_model, generation_response_id 추가 |
| V2 기본값·제약 | generation_attempts는 NOT NULL·기본 0. 생성 선점 조건 제약 확인 |
| V3 버전 | dog_behavior_profiles.revision은 NOT NULL·기본 1·양수 제약 |
| V3 근거 | dog_behavior_evidence 생성. 복합 기본 키와 같은 강아지 관찰 외래 키 확인 |
| 새 인덱스 | messages_active_generation_idx, behavior_evidence_observation_idx 확인 |
| 기존 데이터 | 사용자 4·보호소 2·강아지 5·관찰 25 유지. 강아지·관찰의 별도 전후 지문도 일치 |
| 대화·행동 설정 | 대화방·메시지·행동 설정·행동 근거 모두 0. 샘플을 새로 넣지 않음 |
| RLS | 업무 테이블 12개 + Flyway 이력, 총 13개 활성화 |
| anon / authenticated | schema USAGE 없음, 읽기·쓰기 등 테이블 접근 권한 0개 |

원격에서는 커밋 뒤 별도 읽기 쿼리로 이력·컬럼·기본값·제약·인덱스·RLS·권한·샘플을 다시 확인했어. DB 비밀번호나 API 키는 가져오거나 저장하지 않았어.

적용 도구는 CI의 임시 PostgreSQL에서 먼저 실행해 실제 Flyway `validate`와 추가 마이그레이션 0건을 확인했어. 이미 적용된 DB의 재실행 거절, 잘못된 이력 차단, V3 실패 시 앞서 실행한 V2와 이력까지 롤백되는 것도 검사했어. 원격 JDBC로 Flyway를 직접 실행한 것은 아니야.

## 다음에 연결할 것

DB는 이제 V3까지 준비됐어. Spring Boot 원격 JDBC 연결과 서버 배포, 실제 AI·사진 Storage 설정 및 전체 API 호출 검증은 별도야. **RN 애니메이션 연결은 프론트의 캐릭터·동작 재생 구조가 준비된 뒤 진행해.** 백엔드는 8종 행동 설정 API와 연결 문서를 제공하는 단계까지 완료했어.

## 같은 V1 개발 환경에 사용할 적용 SQL

```sh
cd backend
./gradlew exportSupabaseUpgrade
```

`build/supabase-v2-v3-upgrade.sql`을 만들며 DB에 자동 접속하지 않아. 정확한 V1 이력만 허용하고, 테이블 잠금·짧은 시간 제한·전후 내용 비교·원자적 커밋을 사용해. 실행 전 대상 프로젝트와 현재 상태를 반드시 확인해. 이번 프로젝트는 이미 완료됐으므로 **다시 실행하지 않아도 돼**. V2만 적용된 환경이나 다음 버전은 이 일회성 도구의 대상이 아니야.

## B-11 서버 실제 연결 · 2026.09.21

이후 B-11에서 로컬 Spring Boot와 이 개발 DB의 세션 풀러를 실제로 연결했다. readiness UP, 공개 보호소·강아지·행동 조회와 미인증 경계 30개를 확인했다. 기존 데이터나 구조·권한을 바꾸지 않았으며, [실행 방법과 확인 범위](supabase-server-connection.md)를 별도로 정리했다. 실제 로그인 저장·AI·Storage·외부 배포는 아직 별도다.
