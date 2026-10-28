# 이미지와 에셋

- **정원·로딩 v2**: 사용자가 승인한 자체 디자인 출력에서 추출한 156개 정원 스프라이트와 강아지 없는 120프레임 전후경. 원본 ZIP 해시는 `backend/scripts/import_meeting_screen_bundles.py`에 기록한다. 로딩 글자는 Galmuri11로 렌더링되었다. [Galmuri](https://github.com/quiple/galmuri)는 SIL Open Font License 1.1이며 이 배포에는 폰트 파일을 포함하지 않는다.

- **플레이어 애니메이션**: 사용자가 제공한 `AnimationSheet.png`를 `player-animation-sheet.png`로 보관했습니다. 24×24 원본 프레임에서 IDLE·WALK·RUN·PUSH·PULL·SIT를 추출하고, 발이 잘리지 않도록 24×32 셀로 재배치한 결과가 `player-walk-atlas.png`입니다. 원본 제작자와 이 파일에 적용되는 라이선스는 아직 확인되지 않았으며, 이 저장소는 별도 재배포 권한을 부여하지 않습니다.
- **도트 강아지·운동장·대화 배경**: 이 시안 작업에서 코드로 그린 PNG입니다. 생성 도구는 `work/mobile-concept/make-*.py`에 있습니다.
- **프로필 참고 사진**: [jigsawpieces/dog-api-images](https://github.com/jigsawpieces/dog-api-images/tree/main/mix). 가상 강아지 프로필을 설명하기 위한 참고 사진이며, 실제 연결 보호소의 입양 공고 사진이 아닙니다. CDN에서 불러옵니다.
- **글꼴**: Noto Sans KR(Google Fonts), Galmuri(jsDelivr).
- **아이콘**: Lucide. 브라우저 미리보기 틀은 CDN 리소스를 사용합니다.

이 문서는 출처와 수정 내역을 기록하며, 제삼자 에셋에 새로운 이용 허가를 부여하지 않습니다.
