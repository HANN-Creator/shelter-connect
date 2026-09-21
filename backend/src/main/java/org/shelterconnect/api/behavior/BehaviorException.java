package org.shelterconnect.api.behavior;

final class BehaviorException extends RuntimeException {
	final int status;
	final String code;
	BehaviorException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
	static BehaviorException invalid() { return new BehaviorException(400,"INVALID_BEHAVIOR","행동 설정의 항목과 범위를 확인해 주세요."); }
	static BehaviorException stale() { return new BehaviorException(409,"STALE_RESOURCE","다른 수정 내용이 있어요. 다시 조회한 뒤 저장해 주세요."); }
	static BehaviorException evidence() { return new BehaviorException(409,"INVALID_BEHAVIOR_EVIDENCE","해당 강아지의 확인된 관찰 기록을 선택해 주세요."); }
}
