package org.shelterconnect.api.adoption;

final class AdoptionNoteException extends RuntimeException {
	final int status;
	final String code;
	AdoptionNoteException(int status, String code, String message) {
		super(message); this.status = status; this.code = code;
	}
	static AdoptionNoteException invalid() {
		return new AdoptionNoteException(400, "INVALID_REQUEST", "요청 항목과 값을 확인해 주세요.");
	}
	static AdoptionNoteException missing() {
		return new AdoptionNoteException(404, "NOTE_NOT_FOUND", "저장한 입양 준비 메모가 없어요.");
	}
	static AdoptionNoteException conflict() {
		return new AdoptionNoteException(409, "NOTE_VERSION_CONFLICT", "메모가 변경되었어요. 다시 조회한 뒤 수정해 주세요.");
	}
}
