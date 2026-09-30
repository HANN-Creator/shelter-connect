# Jackson 보안 패치 · B-35A

2026-10-01 B-35의 CI에서 기존 런타임 Jackson 2.21.5/3.1.5에 대한 공지 5건이 검출됐다.
API용 Jackson 3.x와 Swagger용 2.x를 각각 3.1.7/2.21.7 BOM으로 올려 같은 계열의 호환 패치를 적용한다.
OSV 예외 목록을 추가하거나 실패를 무시하지 않는다. API·DB 계약은 바꾸지 않는다.

공식 공지와 수정 버전:

- [GHSA-cxp5-3px4-pw24](https://github.com/advisories/GHSA-cxp5-3px4-pw24): 객체 참조 해석 시 CPU 사용량. 2.21.7/3.1.7 수정.
- [GHSA-wv8q-qhhj-9h54](https://github.com/advisories/GHSA-wv8q-qhhj-9h54): 알 수 없는 타입 ID 캐시 증가. 2.21.7/3.1.7 수정.
- [GHSA-gx83-3vf8-gh7j](https://github.com/advisories/GHSA-gx83-3vf8-gh7j): Comparable 기본 타입 검사. 2.21.6/3.1.6 이상 수정.
- [GHSA-q4xh-88c3-wmh7](https://github.com/advisories/GHSA-q4xh-88c3-wmh7): XML 시간 숫자 변환 입력 길이. 2.21.6/3.1.6 이상 수정.
- [GHSA-wjgm-6hv5-3cvf](https://github.com/advisories/GHSA-wjgm-6hv5-3cvf): Path 스킴 제한. 2.21.6/3.1.6 이상 수정.

일부 공지는 해당 Java 타입/다형성 설정을 사용하는 경우에 적용된다. 이번 변경은 실제 공격 가능성을 확인했다는 의미가 아니라 런타임의 알려진 취약 버전을 제거하는 패치다.
일반 API의 요청 크기·빈도 제한과 인증 경계는 유지한다. 최종 검증·병합·배포 SHA는 작업 카드와 PR에 기록한다.

노션: https://app.notion.com/p/3eb5b2d1a55f807d91eed45ec11ca3c3
