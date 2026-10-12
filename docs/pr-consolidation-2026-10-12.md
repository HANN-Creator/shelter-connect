# B99 PR 변경 중복과 현재 에셋 경로 정리

기준: `main`의 `adee31808db4da8123e23cdcc1d96ae1a4e2d34f`, 2026-10-12.
노션: 연결 도구가 없어 미동기화. GitHub 결과와 로컬 문서만 기록한다.

## 확인 범위와 결과

- 백엔드 저장소의 PR #1~#110 전체 목록·상태·본문·병합 SHA·변경 파일과 병합 패치 ID를 비교했다.
  모든 과거 코드 줄의 재보안 감사나 전체 제품 기능 재검증을 뜻하지 않는다.
- 시작 시 109개 병합, 1개 열린 Draft. 동일 변경 패치 ID를 가진 중복 병합은 0개다.
- #79는 사용자가 실제 그림체를 거절한 별도 실험이다. 병합하지 않고 종료했으며 커밋·실패 이미지·브랜치는 보존했다.
- 반복의 핵심은 같은 패치 재병합보다 검수·보완·문구 실패에 대한 후속 분기가 누적된 것이다.
  `StyledAssetStore`는 40개 PR, `StyledAssetWorker`는 30개, `StyledQualityAgent`는 14개에서 변경됐다.

## 통합한 부분

| 대상 | 발견한 중복·혼동 | 이번 정리 |
| --- | --- | --- |
| 작업 정책 생성 | 과거 정책에서 출발해 여백·보완 한도를 다시 덮어쓰고, 넣었던 눈 편집·학습 복구 설정을 곧바로 제거 | `StyledAssetPolicy`에서 공통 필드와 현재/과거 정책을 명시적으로 조립. 저장 값과 복구 계약 유지 |
| 문구 생성·관찰 | 경고 모드와 차단 모드에 캐시 조회, 작성, 초안 저장, 완료·실패 기록이 각각 존재 | `StyledLessonPromptComposer`의 공통 처리로 통합. 경고는 관찰 1회, 기존 차단 작업만 이전 의미 재작성 횟수 유지 |
| 과거 조사 문서 | 여러 시점의 ‘현재 동작’·재배포 안내가 함께 남아 있음 | 현재 서버 기준은 `asset-pipeline-current.md`로 모으고 과거 조사 문서에 이력임을 표시 |
| 열린 그림체 PR | 거절된 실험 #79가 여전히 Draft | 종료. 승인 스타일에 반영하지 않음 |

## 유지한 부분과 이유

- #21~#49의 64px/하네스/CLI와 #50 이후 native32 워커는 데이터·실행 경로가 다르다.
  저장된 이전 결과 조회와 수동 도구를 위한 코드이므로 이름이 비슷하다는 이유로 삭제하지 않는다.
- #80~#109의 엄격 검수·재개 경로는 과거 DB 작업에 고정된 정책을 처리한다.
  현재 일반 작업은 #110 경고 경로를 사용한다. 이전 작업은 담당자 전환 API를 사용해야 하므로 호환 경로는 남긴다.
- DB 마이그레이션 V1~V24, 과거 영수증·결과·학습 자료는 이력이다. 중복 코드처럼 삭제하거나 수정하지 않는다.
- 보안·문구 길이·파일 무결성 검사는 시각 미관 판단과 다르다. 경고 정책으로 바뀌어도 필요하다.
- 4방향 그림체·품질 규칙 v43·원본·공개된 이미지·생성 모델·기본 경고 설정은 이번 정리로 변경하지 않는다.

## 현재 기준

[서버 에셋 파이프라인 현재 기준](asset-pipeline-current.md)과 [경고 전환 API](visual-quality-warnings.md)를 따른다.
과거 PR은 아래에서 원인·변경 이력을 찾는 용도로 보존한다. 이미 병합된 PR을 삭제하거나 재병합하지 않는다.

2026-10-12 실서버 읽기 확인: readiness `UP`, 공개 OpenAPI에 경고 전환/조회 2개 API가 확인됐다.
이 확인은 새 강아지의 실제 생성·승인·앱 재생 완료를 뜻하지 않는다.
이번 코드 정리의 검증·병합·배포 경계는 B99 PR에 기록한다.

## 관련 변경 묶음

| 묶음 | PR | 판단 |
| --- | --- | --- |
| 진행 규칙 | #1, #6 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 서버·DB·인증·조회·대화 | #2, #3, #4, #5, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 초기 에셋·하네스·도구 | #21, #22, #23, #24, #25, #26, #27, #28, #29, #30, #31, #32, #33, #34, #35, #47, #48, #49 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 보안·개인·커뮤니티 | #36, #37, #38, #39, #40, #41, #42, #43, #44, #45, #46 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 4방향 저장·행동 선택 | #50, #51, #52 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 동작 검수·잘림·꼬리 보완 | #53, #54, #55, #56, #57, #58, #59, #67, #68, #69, #70, #71, #72, #73, #74, #77 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 학습·기본 도트·승인 | #60, #61, #62, #63, #64, #65, #66, #76, #78 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 거절된 그림체 실험 | #79 | 종료·미병합 |
| 운영 검수·복구·꼬리 오판 | #80, #81, #82, #83, #84, #85, #86, #87, #88, #89, #90, #91, #92, #93, #94, #95, #98, #99, #100, #101, #102, #103, #108 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 배포 다운로드 오류 | #96, #97 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 문구 압축·검증 | #75, #104, #105, #107, #109 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 사진 자동 입력 | #106 | 관련 후속 변경으로 통합된 이력, 병합 유지 |
| 현재 경고 정책 | #110 | 현재 기본 정책 |

## 전체 PR 대조표

분류는 작업 목적 기준이다. ‘유지’는 병합 이력을 보존한다는 뜻이며, 모든 이전 정책을 새 작업에서 실행한다는 뜻이 아니다.

| PR | 제목 | 분류 | 정리 후 상태 | 병합 커밋 |
| --- | --- | --- | --- | --- |
| [#1](https://github.com/HANN-Creator/shelter-connect/pull/1) | [C-05] 백엔드 구현 순서와 브랜치·노션 진행 규칙 | 진행 규칙 | 병합 이력 유지 | `597d82c` |
| [#2](https://github.com/HANN-Creator/shelter-connect/pull/2) | [B-00] Spring Boot·Supabase 서버 기본 구성 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `4976955` |
| [#3](https://github.com/HANN-Creator/shelter-connect/pull/3) | [B-01] 보호소·강아지·대화 데이터 구조 정의 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `4ecc73b` |
| [#4](https://github.com/HANN-Creator/shelter-connect/pull/4) | [B-02] 가상 보호소 2곳·강아지 5마리 샘플 데이터 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `5309d1e` |
| [#5](https://github.com/HANN-Creator/shelter-connect/pull/5) | [B-02A] Supabase 개발 DB에 구조와 샘플 초기 적용 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `bee0590` |
| [#6](https://github.com/HANN-Creator/shelter-connect/pull/6) | [C-05] 백엔드 브랜치 이름과 작업 기록 표기 정리 | 진행 규칙 | 병합 이력 유지 | `206c48f` |
| [#7](https://github.com/HANN-Creator/shelter-connect/pull/7) | [B-03] 보호소·강아지 목록과 프로필 조회 API | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `60f70bf` |
| [#8](https://github.com/HANN-Creator/shelter-connect/pull/8) | [B-04] Supabase 인증과 보호소 권한 검사 추가 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `fada410` |
| [#9](https://github.com/HANN-Creator/shelter-connect/pull/9) | [B-05] 보호소 강아지와 관찰 기록 관리 API 추가 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `e6d6166` |
| [#10](https://github.com/HANN-Creator/shelter-connect/pull/10) | [B-06] 본인 대화방과 메시지 저장 API 추가 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `b72f18a` |
| [#11](https://github.com/HANN-Creator/shelter-connect/pull/11) | [B-07] 확인된 강아지 기록 기반 AI 답변 연결 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `99a2e5b` |
| [#12](https://github.com/HANN-Creator/shelter-connect/pull/12) | [B-08] 대화 후 허가된 강아지 사진 조회 추가 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `5ffd02a` |
| [#13](https://github.com/HANN-Creator/shelter-connect/pull/13) | [B-10] 강아지 8종 행동 설정 저장과 확인 API 추가 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `56602d1` |
| [#14](https://github.com/HANN-Creator/shelter-connect/pull/14) | [B-10A] Supabase 개발 DB V2·V3 적용 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `c5e9695` |
| [#15](https://github.com/HANN-Creator/shelter-connect/pull/15) | [B-09] 입양 준비 메모 저장·수정·조회 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `0f51616` |
| [#16](https://github.com/HANN-Creator/shelter-connect/pull/16) | [B-11] Spring Boot와 Supabase 개발 DB 실제 연결 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `302e58d` |
| [#17](https://github.com/HANN-Creator/shelter-connect/pull/17) | [B-12] Supabase 비공개 사진 저장소 연결 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `045cc50` |
| [#18](https://github.com/HANN-Creator/shelter-connect/pull/18) | [B-13] 실제 로그인과 개인 기록 저장 검증 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `63ee8c3` |
| [#19](https://github.com/HANN-Creator/shelter-connect/pull/19) | [Q-02] 실제 AI 답변 검증과 말투·근거 활용 보완 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `abdc818` |
| [#20](https://github.com/HANN-Creator/shelter-connect/pull/20) | [B-14] 무료 개발 서버 배포와 원격 저장 검증 | 서버·DB·인증·조회·대화 | 병합 이력 유지 | `13fc5b5` |
| [#21](https://github.com/HANN-Creator/shelter-connect/pull/21) | [B-15] PixelLab 8종 에셋 생성과 허가 기반 자동 등록 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `cdc9ebc` |
| [#22](https://github.com/HANN-Creator/shelter-connect/pull/22) | [B-16] 특성별 행동 선택과 모션 하네스 서버 연결 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `3b45ca8` |
| [#23](https://github.com/HANN-Creator/shelter-connect/pull/23) | [B-17] 합성 후 끊긴 강아지 외곽선 복원 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `74995f9` |
| [#24](https://github.com/HANN-Creator/shelter-connect/pull/24) | [B-18] 강아지 행동별 재생 명세서 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `d7a859d` |
| [#25](https://github.com/HANN-Creator/shelter-connect/pull/25) | 보호소 사진·특징 등록을 에셋 생성 흐름에 연결 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `cebc6dd` |
| [#26](https://github.com/HANN-Creator/shelter-connect/pull/26) | 백엔드 46개 API Swagger 명세서 제공 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `4cda28b` |
| [#27](https://github.com/HANN-Creator/shelter-connect/pull/27) | Swagger AI 생성·사진 오류 응답 누락 보완 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `3846bdd` |
| [#28](https://github.com/HANN-Creator/shelter-connect/pull/28) | 사진 재요청과 생성 워커의 잠금 충돌 수정 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `1910366` |
| [#29](https://github.com/HANN-Creator/shelter-connect/pull/29) | [B-22] 사진부터 동작 에셋까지 실서버 API 검증 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `532b918` |
| [#30](https://github.com/HANN-Creator/shelter-connect/pull/30) | [B-23] 동작 합성 중 프레임 가장자리 잘림 수정 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `c024efe` |
| [#31](https://github.com/HANN-Creator/shelter-connect/pull/31) | [B-24] 기준 도트 생성에 기존 그림체 참조 연결 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `7dc343e` |
| [#32](https://github.com/HANN-Creator/shelter-connect/pull/32) | [B-25] 사진 외형 분석과 얼굴 참고 이미지 자동 생성 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `068fc4f` |
| [#33](https://github.com/HANN-Creator/shelter-connect/pull/33) | [B-26] 실사진부터 동작 에셋 조회까지 검증 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `745e75f` |
| [#34](https://github.com/HANN-Creator/shelter-connect/pull/34) | [B-27] 원본 다리 디테일 보존과 48프레임 모션 적용 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `5a8fc60` |
| [#35](https://github.com/HANN-Creator/shelter-connect/pull/35) | [B-28] 걷기 다리 왜곡과 외곽선 덧칠 수정 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `9985792` |
| [#36](https://github.com/HANN-Creator/shelter-connect/pull/36) | [B-29] AI 대화 사용량과 동시 호출 제한 | 보안·개인·커뮤니티 | 병합 이력 유지 | `b4eb6e8` |
| [#37](https://github.com/HANN-Creator/shelter-connect/pull/37) | [B-30] Tomcat·Pillow 보안 패치 적용 | 보안·개인·커뮤니티 | 병합 이력 유지 | `4fcb412` |
| [#38](https://github.com/HANN-Creator/shelter-connect/pull/38) | fix: DB 실행 권한 분리와 TLS 인증서 검증 (B-31) | 보안·개인·커뮤니티 | 병합 이력 유지 | `8aa7465` |
| [#39](https://github.com/HANN-Creator/shelter-connect/pull/39) | [B-32] API 요청 제한과 오류 로그 보완 | 보안·개인·커뮤니티 | 병합 이력 유지 | `590f885` |
| [#40](https://github.com/HANN-Creator/shelter-connect/pull/40) | [B-33] 의존성 보안 검사 자동화 | 보안·개인·커뮤니티 | 병합 이력 유지 | `9a81436` |
| [#41](https://github.com/HANN-Creator/shelter-connect/pull/41) | B-34: 브라우저에서 실제 입양 흐름 검증 | 보안·개인·커뮤니티 | 병합 이력 유지 | `2410d4e` |
| [#42](https://github.com/HANN-Creator/shelter-connect/pull/42) | Add personal features and nearby shelter discovery | 보안·개인·커뮤니티 | 병합 이력 유지 | `729d470` |
| [#43](https://github.com/HANN-Creator/shelter-connect/pull/43) | Patch Jackson 2 and 3 security advisories | 보안·개인·커뮤니티 | 병합 이력 유지 | `6392055` |
| [#44](https://github.com/HANN-Creator/shelter-connect/pull/44) | [B-36] 커뮤니티 게시글·목격 제보·신고·사진 API | 보안·개인·커뮤니티 | 병합 이력 유지 | `4696adf` |
| [#45](https://github.com/HANN-Creator/shelter-connect/pull/45) | [B-37] 이웃 문의방·메시지·읽음 API | 보안·개인·커뮤니티 | 병합 이력 유지 | `ced951b` |
| [#46](https://github.com/HANN-Creator/shelter-connect/pull/46) | [B-37A] 미게시 사진의 운영자 접근 차단 | 보안·개인·커뮤니티 | 병합 이력 유지 | `bea8ba5` |
| [#47](https://github.com/HANN-Creator/shelter-connect/pull/47) | [B-38] 백엔드 하네스에 강아지별 32×32 맵 도트 추가 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `4eb936d` |
| [#48](https://github.com/HANN-Creator/shelter-connect/pull/48) | [B-39] 32px 도트 눈·주둥이·다리 자동 보정 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `27a38f8` |
| [#49](https://github.com/HANN-Creator/shelter-connect/pull/49) | [B-15] 승인 도트 스타일과 실제 사진을 함께 사용하는 32px 생성 도구 | 초기 에셋·하네스·도구 | 병합 이력 유지 | `1804dd6` |
| [#50](https://github.com/HANN-Creator/shelter-connect/pull/50) | [B-40] 32px 4방향 에셋 생성·영구 저장·재사용 연결 | 4방향 저장·행동 선택 | 병합 이력 유지 | `71ee555` |
| [#51](https://github.com/HANN-Creator/shelter-connect/pull/51) | [B-41] 에셋 생성 일일 요청 제한 제거 | 4방향 저장·행동 선택 | 병합 이력 유지 | `24c0f74` |
| [#52](https://github.com/HANN-Creator/shelter-connect/pull/52) | [B-42] Luna 특징에 맞는 4방향 행동만 생성 | 4방향 저장·행동 선택 | 병합 이력 유지 | `71b1ce4` |
| [#53](https://github.com/HANN-Creator/shelter-connect/pull/53) | [B-43] 생성 시트 자동 검수·보완과 네 방향 꼬리 기준 통일 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `45d2836` |
| [#54](https://github.com/HANN-Creator/shelter-connect/pull/54) | [B-44] 발견된 스프라이트 결함을 공통 예방 규칙과 회귀 하네스에 반영 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `50b85d7` |
| [#55](https://github.com/HANN-Creator/shelter-connect/pull/55) | [B-45] 실제 정면 꼬리 검수 누락 차단 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `5160f4c` |
| [#56](https://github.com/HANN-Creator/shelter-connect/pull/56) | [B-46] 실제 꼬리 잘림 편집 복구와 원본 도트 보존 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `e2104b1` |
| [#57](https://github.com/HANN-Creator/shelter-connect/pull/57) | [B-47] 서버 꼬리 보완에 전체 프레임 편집 연결 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `ef2c421` |
| [#58](https://github.com/HANN-Creator/shelter-connect/pull/58) | [B-48] 가만히 쉬기와 꼬리 흔들기 구분 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `dfff237` |
| [#59](https://github.com/HANN-Creator/shelter-connect/pull/59) | [B-49] 앉기 꼬리 여백과 마지막 프레임 검증 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `07ef080` |
| [#60](https://github.com/HANN-Creator/shelter-connect/pull/60) | [B-50] 스프라이트 품질 규칙 자동 학습·검증·적용 | 학습·기본 도트·승인 | 병합 이력 유지 | `853cca4` |
| [#61](https://github.com/HANN-Creator/shelter-connect/pull/61) | [B-50A] 실제 Luna 규칙 학습 검증과 방향별 fixture 수정 | 학습·기본 도트·승인 | 병합 이력 유지 | `8012b83` |
| [#62](https://github.com/HANN-Creator/shelter-connect/pull/62) | [B-51] Block unreadable seed eyes before dog motion generation | 학습·기본 도트·승인 | 병합 이력 유지 | `57cd6dc` |
| [#63](https://github.com/HANN-Creator/shelter-connect/pull/63) | [B-51A] 기준 도트 뒷모습 오판 수정과 저장 원본 재검수 | 학습·기본 도트·승인 | 병합 이력 유지 | `5675e34` |
| [#64](https://github.com/HANN-Creator/shelter-connect/pull/64) | [B-52] 기본 도트 생성·눈 검수에 자동 학습 연결 | 학습·기본 도트·승인 | 병합 이력 유지 | `9a78e8e` |
| [#65](https://github.com/HANN-Creator/shelter-connect/pull/65) | [B-53] Register approved and failed base sprites for learning | 학습·기본 도트·승인 | 병합 이력 유지 | `fc4fc6d` |
| [#66](https://github.com/HANN-Creator/shelter-connect/pull/66) | [B-53A] 실제 기본 도트 학습 자료 업로드 413 수정 | 학습·기본 도트·승인 | 병합 이력 유지 | `f38c299` |
| [#67](https://github.com/HANN-Creator/shelter-connect/pull/67) | [B-54] 반복 실패한 쉬기 동작의 마지막 보완을 전체 프레임 편집으로 전환 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `756cf53` |
| [#68](https://github.com/HANN-Creator/shelter-connect/pull/68) | [B-55] 반복된 동작 잘림의 마지막 보완을 전체 프레임 편집으로 전환 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `a893936` |
| [#69](https://github.com/HANN-Creator/shelter-connect/pull/69) | [B-56] 실제 동작 편집 결과를 회귀 검증에 기록 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `ae63f44` |
| [#70](https://github.com/HANN-Creator/shelter-connect/pull/70) | [B-57] 실패 동작 기록을 보존하는 추가 보완과 기준 도트 재사용 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `1f774a9` |
| [#71](https://github.com/HANN-Creator/shelter-connect/pull/71) | [B-58] 작은 명암 변화를 큰 동작으로 오판하지 않도록 픽셀 근거 전달 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `ececa9f` |
| [#72](https://github.com/HANN-Creator/shelter-connect/pull/72) | fix: compare sprite frames beside matching direction references | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `442ad86` |
| [#73](https://github.com/HANN-Creator/shelter-connect/pull/73) | [B-60] 동작별 검수와 기존 쉬기 보완 경로 분리 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `c8a8a89` |
| [#74](https://github.com/HANN-Creator/shelter-connect/pull/74) | B-61 검증된 학습 규칙으로 실패한 쉬기 보완 재개 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `f625447` |
| [#75](https://github.com/HANN-Creator/shelter-connect/pull/75) | B-62 전체 학습 규칙을 AI로 간결화해 생성·보완에 반영 | 문구 압축·검증 | 병합 이력 유지 | `5370ba6` |
| [#76](https://github.com/HANN-Creator/shelter-connect/pull/76) | [B-63] 기본 도트와 최종 팩 품질 통과 자동 승인 | 학습·기본 도트·승인 | 병합 이력 유지 | `096b874` |
| [#77](https://github.com/HANN-Creator/shelter-connect/pull/77) | [B-64] 걷기·앉기 학습과 추가 보완 경로 복구 | 동작 검수·잘림·꼬리 보완 | 병합 이력 유지 | `15bffea` |
| [#78](https://github.com/HANN-Creator/shelter-connect/pull/78) | [B-65] 작은 기준 도트 눈을 원본 보존 편집으로 보완 | 학습·기본 도트·승인 | 병합 이력 유지 | `32d3104` |
| [#79](https://github.com/HANN-Creator/shelter-connect/pull/79) | [B-66] 단순한 도트 그림체와 양옆 꼬리 검수 연결 | 거절된 그림체 실험 | 종료·미병합 | — |
| [#80](https://github.com/HANN-Creator/shelter-connect/pull/80) | [B-67] 사진 기반 스프라이트 자동 검수·보완을 운영 worker에 연결 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `f88d3d1` |
| [#81](https://github.com/HANN-Creator/shelter-connect/pull/81) | [B-68] 통과 방향을 보존하며 기본 도트 결함만 선택 보완 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `173376e` |
| [#82](https://github.com/HANN-Creator/shelter-connect/pull/82) | [B-69] 기본 도트 꼬리 검수에 사진·네이티브 픽셀 근거 추가 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `568f3ad` |
| [#83](https://github.com/HANN-Creator/shelter-connect/pull/83) | [B-70] 꼬리 좌표 재검증과 확정 결함 보완 분리 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `6f2b94b` |
| [#84](https://github.com/HANN-Creator/shelter-connect/pull/84) | [B-71] 보완된 기본 도트의 재검수 실패 수정 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `48a80c3` |
| [#85](https://github.com/HANN-Creator/shelter-connect/pull/85) | [B-72] 꼬리 좌표를 방향별 확대 원본으로 재검수 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `a030adb` |
| [#86](https://github.com/HANN-Creator/shelter-connect/pull/86) | [B-73] 꼬리·동작 검수 충돌과 잘못된 보완·학습 차단 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `76465ea` |
| [#87](https://github.com/HANN-Creator/shelter-connect/pull/87) | [B-74] 기준 도트 재검수 뒤 남은 예산으로 자동 보완 재개 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `cf06657` |
| [#88](https://github.com/HANN-Creator/shelter-connect/pull/88) | [B-75] 동작 검수 충돌 복구와 제한된 자동 보완 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `1e5a611` |
| [#89](https://github.com/HANN-Creator/shelter-connect/pull/89) | [B-76] 정상 꼬리의 중간 몸통 연결 판별 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `23d25e5` |
| [#90](https://github.com/HANN-Creator/shelter-connect/pull/90) | [B-77] 미관 경고를 자동 보완·승인 차단과 분리 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `511fe5b` |
| [#91](https://github.com/HANN-Creator/shelter-connect/pull/91) | [B-78] 무늬 경고가 외형 불일치로 다시 차단되는 오류 수정 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `8409ea6` |
| [#92](https://github.com/HANN-Creator/shelter-connect/pull/92) | fix: 확정 꼬리 결함의 보완을 중복 외형 불확실과 분리 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `9dcdd9f` |
| [#93](https://github.com/HANN-Creator/shelter-connect/pull/93) | [B-80] 저장된 이미지 검수의 시간 초과 복구 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `d12cdfc` |
| [#94](https://github.com/HANN-Creator/shelter-connect/pull/94) | [B-81] 뒤쪽 동작을 얼굴로 오인하는 검수 수정 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `7ab541d` |
| [#95](https://github.com/HANN-Creator/shelter-connect/pull/95) | [B-82] 보완 소진 후 쉬기 움직임 충돌의 정지 대안 검수 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `bb9bc39` |
| [#96](https://github.com/HANN-Creator/shelter-connect/pull/96) | [B-83] 배포 의존성 캐시와 HTTP 429 지연 재시도 | 배포 다운로드 오류 | 병합 이력 유지 | `abc5a6f` |
| [#97](https://github.com/HANN-Creator/shelter-connect/pull/97) | [B-84] Render 최초 빌드의 Maven 429를 독립 캐시로 해결 | 배포 다운로드 오류 | 병합 이력 유지 | `f08146f` |
| [#98](https://github.com/HANN-Creator/shelter-connect/pull/98) | [B-85] 확정 결함 보완과 불확실 검수 처리 분리 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `c05a84d` |
| [#99](https://github.com/HANN-Creator/shelter-connect/pull/99) | [B-86] 쉬기·앉기 보완 경로와 걷기 근거 검수 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `c01cff7` |
| [#100](https://github.com/HANN-Creator/shelter-connect/pull/100) | [B-87] 쉬기 대체 계획의 명암 경고 중복 차단 수정 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `a18fa2d` |
| [#101](https://github.com/HANN-Creator/shelter-connect/pull/101) | [B-88] 새 강아지 실서버 검증과 불투명 배경 실패 회귀 기록 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `663441d` |
| [#102](https://github.com/HANN-Creator/shelter-connect/pull/102) | [B-89] 배경 오류 자동 복구·쉬기 재생성·학습 규칙 재검증 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `e2865f9` |
| [#103](https://github.com/HANN-Creator/shelter-connect/pull/103) | [B-91] 정상 꼬리의 좌우 대칭 검사 오탐 수정 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `0e48406` |
| [#104](https://github.com/HANN-Creator/shelter-connect/pull/104) | [B-92] 전체 생성 문구 압축과 기존 보완 재개 | 문구 압축·검증 | 병합 이력 유지 | `d95a613` |
| [#105](https://github.com/HANN-Creator/shelter-connect/pull/105) | [B-93] 생성 문구의 결함 코드·형식 검사 충돌 수정 | 문구 압축·검증 | 병합 이력 유지 | `82eb961` |
| [#106](https://github.com/HANN-Creator/shelter-connect/pull/106) | B94: 사진 ID로 native32 외형 분석과 생성을 연결 | 사진 자동 입력 | 병합 이력 유지 | `de8e0c4` |
| [#107](https://github.com/HANN-Creator/shelter-connect/pull/107) | [B-95] 생성 문구 길이 자동 복구와 전체 조건 재검증 | 문구 압축·검증 | 병합 이력 유지 | `d46259e` |
| [#108](https://github.com/HANN-Creator/shelter-connect/pull/108) | fix(B96): 동일 꼬리 오판 조정과 기준 자세 비교 보완 | 운영 검수·복구·꼬리 오판 | 병합 이력 유지 | `ba9d1ea` |
| [#109](https://github.com/HANN-Creator/shelter-connect/pull/109) | [B97] 생성 문구의 원문 범위 보존과 v4 실패 재개 | 문구 압축·검증 | 병합 이력 유지 | `3e78e1d` |
| [#110](https://github.com/HANN-Creator/shelter-connect/pull/110) | [B-98] 이미지 품질 지적을 경고로 남기고 자동 보완 반복 중단 | 현재 경고 정책 | 병합 이력 유지 | `adee318` |
