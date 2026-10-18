# WIN-01 Windows 실행 호환

미니PC의 기존 Java 21·PostgreSQL 서비스를 유지한다. Unix 전용 fcntl 잠금을 플랫폼별 잠금으로 바꾸고 Windows에서는 동일 파일의 첫 바이트를 msvcrt로 잠가 유료 작업 중복 실행을 막는다. UTF-8 JSON 저널과 Windows 절대 경로의 인증서 테스트 입력을 지원한다. 기존 Unix 경로는 유지한다.

품질 기준·기존 증거 파일은 수정하지 않으며 .gitattributes로 byte 단위 증거의 LF를 보존한다. 실제 유료 AI 호출 또는 에셋 작업자 활성화는 이 변경의 검증과 별개다. 노션 도구가 제공되지 않아 외부 카드는 미동기화다.
