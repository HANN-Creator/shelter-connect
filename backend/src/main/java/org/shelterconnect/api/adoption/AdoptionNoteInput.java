package org.shelterconnect.api.adoption;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

final class AdoptionNoteInput {
	private static final Set<String> FIELDS = Set.of("questions", "carePlan", "checklist", "expectedUpdatedAt");
	private static final Set<String> CHECKS = Set.of("householdDiscussed", "housingChecked",
			"careTimePlanned", "budgetPlanned", "shelterQuestionsPrepared");
	private AdoptionNoteInput() {}
	record Save(String questions, String carePlan, Map<String, Boolean> checklist, Instant expectedUpdatedAt) {}
	record Cursor(Instant at, UUID id) {}

	static Save save(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != FIELDS.size()
				|| !FIELDS.stream().allMatch(body::has)) throw AdoptionNoteException.invalid();
		var checks = body.get("checklist");
		if (!checks.isObject() || checks.size() > CHECKS.size()) throw AdoptionNoteException.invalid();
		Map<String, Boolean> values = new TreeMap<>();
		for (var entry : checks.properties()) {
			if (!CHECKS.contains(entry.getKey()) || !entry.getValue().isBoolean()) throw AdoptionNoteException.invalid();
			values.put(entry.getKey(), entry.getValue().asBoolean());
		}
		var expected = body.get("expectedUpdatedAt");
		if (!expected.isNull() && !expected.isString()) throw AdoptionNoteException.invalid();
		return new Save(text(body.get("questions"), 5000), text(body.get("carePlan"), 10000),
				Map.copyOf(values), expected.isNull() ? null : instant(expected.asString()));
	}
	private static String text(JsonNode node, int max) {
		if (!node.isString()) throw AdoptionNoteException.invalid();
		String value = node.asString();
		if (value.indexOf('\0') >= 0 || value.codePointCount(0, value.length()) > max) throw AdoptionNoteException.invalid();
		return value;
	}
	static UUID id(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException | NullPointerException ex) { throw AdoptionNoteException.invalid(); }
	}
	private static Instant instant(String value) {
		try {
			Instant at = Instant.parse(value);
			if (at.isBefore(Instant.EPOCH) || at.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))
					|| at.getNano() % 1000 != 0) throw new IllegalArgumentException();
			return at;
		} catch (RuntimeException ex) { throw AdoptionNoteException.invalid(); }
	}
	static int limit(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,2}") || Integer.parseInt(value) < 1 || Integer.parseInt(value) > 50)
			throw AdoptionNoteException.invalid();
		return Integer.parseInt(value);
	}
	static String cursor(UUID user, Instant at, UUID id) {
		String data = "1\nnotes:" + user + "\n" + at + "\n" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
	}
	static Cursor cursor(String value, UUID user) {
		if (value == null) return null;
		try {
			if (value.length() > 300 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\n", -1);
			if (parts.length != 4 || !parts[0].equals("1") || !parts[1].equals("notes:" + user)) throw new IllegalArgumentException();
			return new Cursor(instant(parts[2]), id(parts[3]));
		} catch (RuntimeException ex) {
			throw new AdoptionNoteException(400, "INVALID_CURSOR", "목록의 첫 페이지부터 다시 조회해 주세요.");
		}
	}
}
