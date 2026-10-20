# B-105 공공 동물과 실제 보호소 연결

## 문제와 변경

2026-10-17 배치 50마리는 실제 보호소 이름만 introduction에 기록하고 모두 가상 보호소 소속으로 등록했다. 2026-10-20 [공식 구조동물 API](https://www.data.go.kr/data/15098931/openapi.do)를 `desertion_no`로 개별 조회해 50개 동물번호와 공고번호를 대조했다. 실제 보호소는 `careRegNo` 25개이며 모두 이름·주소·전화번호·관할 지역이 있다. 같은 이름을 가진 보호소를 합치지 않는다.

V26은 `shelters.public_care_reg_no` 고유 식별자, `public_animal_sources` 원본 기록 및 동물번호 고유 연결, `dogs.management_shelter_id`를 추가한다. `dogs.shelter_id`는 실제 보호소를 가리킨다. 공공 조회·저장한 친구·채팅·보호소별 목록은 기존 실제 소속을 사용한다. 기존 생성 관리 범위는 `coalesce(management_shelter_id,shelter_id)`를 사용하므로 생성 담당자의 기존 권한과 사진 허가를 보존한다. 실제 보호소의 담당자 권한을 새로 부여하지 않는다. 실제 보호소가 나중에 직접 관리할 때의 관리권 이관은 별도 절차다.

V27은 **정확한 50개 기존 dog UUID**와 동물번호가 모두 맞는 행만 연결한다. 원래 관리 범위, 비공개·미보관 상태, 실행 중 작업 부재를 확인하고 한 트랜잭션에서 처리한다. 대상이 없는 신규/CI DB에는 보호소나 동물을 추가하지 않는다. 잘못된 기존 대상은 전체 마이그레이션을 롤백한다. 서버 배포의 기존 백업·마이그레이션 사전 검증을 그대로 사용한다.

강아지·사진·작업·스프라이트 ID, 경로·해시·단계·접수·검수·사용 허가를 변경하지 않는다. PixelLab/Luna 호출도 없다. 새 보호소는 PENDING/비공개이며 강아지 공개 상태와 입양 상태를 유지한다. 주소를 임의 좌표로 바꾸지 않는다. 보호소 승인·공개, 실제 좌표 확보는 이 작업에서 수행하지 않는다.

## API와 후속 등록

- `GET /v1/shelter-admin/dogs/{dogId}/public-source`: 담당자에게 실제 shelterId, managementShelterId, animalId, careRegNo, 이름·지역·주소·연락처·공공 상태·조회시각을 반환. 미연결은 404.
- `POST /v1/shelter-admin/dogs/{dogId}/public-source`: `{animalId, expectedUpdatedAt}`. 기존 담당자 권한을 확인한 뒤 서버가 고정 정부 API에서 조회하고, 쓰기 잠금 후 권한·버전을 다시 검사한다. 외부 URL/보호소 메타데이터는 클라이언트 입력으로 받지 않는다.
- B-106부터 새 공공 강아지는 등록 요청의 `publicAnimalId` 또는 기존 `public animal <15자리 번호>.` 출처 표기를 통해 실제 보호소까지 자동 연결된다. 별도의 연결 POST가 필요하지 않다. 자세한 실패·재개 계약은 [자동 연결](automatic-public-shelter-linkage.md)을 따른다. `styled_dog/source.py`는 보호소 코드·주소·전화번호·관할 지역을 보존한다.
- 새 동물 조회에는 서버의 `DATA_GO_KR_SERVICE_KEY`가 필요하다. 미설정이면 503 `PUBLIC_SOURCE_NOT_CONFIGURED`로 중단하며 가상 보호소 연결 완료로 처리하지 않는다. 기존 50마리 V27 적용에는 이미 확인한 원본 스냅샷을 쓰므로 새 설정이 필요 없다.
- 409: `STALE_RESOURCE`, `PUBLIC_SOURCE_PRIVATE_REQUIRED`, `PUBLIC_SOURCE_IDENTITY_MISMATCH`, `PUBLIC_SOURCE_JOB_ACTIVE`, `PUBLIC_SOURCE_INVALID`, `PUBLIC_SOURCE_DUPLICATE`.
- 인증키 포함 URL·응답 예외를 기록하지 않으며, 담당자 이름 등 불필요한 원본 항목도 저장하지 않는다.

## 검증과 반영

50마리/25곳 일괄 변경, 기존 작업/시트 보존, 동일 이름·상이한 코드/동일 코드 중복, 승인 전 비공개, 기존 STAFF 유지·타 사용자와 실제 보호소 전체 권한 차단, 최소 권한 런타임·소속 철회·동시 수정·동물번호 충돌을 PostgreSQL에서 검사한다. 실제 배포 및 50마리 재조회 결과는 PR에 기록한다. 노션 연결 도구가 없어 외부 카드는 미동기화다.
