package org.shelterconnect.api.management;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.management.ManagementResponses.*;

final class ManagementInput {
	private static final Set<String> DOG_FIELDS = Set.of("name", "sex", "breed", "birthDate", "birthDatePrecision",
			"birthDateEstimated", "weightKg", "neutered", "adoptionStatus", "isPublic", "avatarKey", "traitLabels", "introduction");
	private static final Set<String> OBS_FIELDS = Set.of("category", "content", "observedAt", "sourceNote", "status");
	private ManagementInput() {}

	static void dogBody(JsonNode body, boolean create) { object(body, DOG_FIELDS, create ? "shelterId" : "expectedUpdatedAt"); }
	static void observationBody(JsonNode body, boolean create) { object(body, OBS_FIELDS, create ? null : "expectedUpdatedAt"); }

	static DogFields dogFields(JsonNode body, DogFields old) {
		String name = text(body, "name", old.name(), false, 80);
		String sex = option(body, "sex", old.sex(), "MALE", "FEMALE", "UNKNOWN");
		String breed = text(body, "breed", old.breed(), true, 120);
		String precision = option(body, "birthDatePrecision", old.birthDatePrecision(), "UNKNOWN", "YEAR", "MONTH", "DAY");
		LocalDate birth = old.birthDate();
		if (body.has("birthDate")) {
			String value = text(body, "birthDate", null, true, 10);
			try { birth = value == null ? null : LocalDate.parse(value); }
			catch (DateTimeParseException exception) { throw bad("birthDate"); }
		}
		Boolean estimated = bool(body, "birthDateEstimated", old.birthDateEstimated(), true);
		if ("UNKNOWN".equals(precision)) {
			if (birth != null || estimated != null) throw bad("birthDate / birthDatePrecision / birthDateEstimated");
		} else if (birth == null || estimated == null || birth.getYear() < 1 || birth.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))
				|| ("YEAR".equals(precision) && birth.getDayOfYear() != 1)
				|| ("MONTH".equals(precision) && birth.getDayOfMonth() != 1)) {
			throw bad("birthDate / birthDatePrecision / birthDateEstimated");
		}
		BigDecimal weight = old.weightKg();
		if (body.has("weightKg")) {
			var value = body.get("weightKg");
			if (value.isNull()) weight = null;
			else {
				if (!value.isNumber()) throw bad("weightKg");
				weight = value.decimalValue();
				if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("10000")) >= 0 || weight.stripTrailingZeros().scale() > 2) throw bad("weightKg");
			}
		}
		String avatar = text(body, "avatarKey", old.avatarKey(), false, 100);
		if (!avatar.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,99}")) throw bad("avatarKey");
		List<String> labels = old.traitLabels();
		if (body.has("traitLabels")) {
			var value = body.get("traitLabels");
			if (!value.isArray() || value.size() > 8) throw bad("traitLabels");
			var parsed = new ArrayList<String>();
			for (var item : value) {
				if (!item.isString()) throw bad("traitLabels");
				String label = checkedText(item.asString(), "traitLabels", 40);
				if (parsed.contains(label)) throw bad("traitLabels");
				parsed.add(label);
			}
			labels = List.copyOf(parsed);
		}
		return new DogFields(name, sex, breed, birth, precision, estimated, weight,
				bool(body, "neutered", old.neutered(), true), option(body, "adoptionStatus", old.adoptionStatus(), "AVAILABLE", "IN_PROGRESS", "ADOPTED", "PAUSED"),
				bool(body, "isPublic", old.isPublic(), false), avatar, labels, text(body, "introduction", old.introduction(), true, 2000));
	}

	static ObservationFields observationFields(JsonNode body, Observation old) {
		Instant observed = body.has("observedAt") ? instant(body, "observedAt") : old == null ? null : old.observedAt();
		if (observed == null || observed.isAfter(Instant.now())) throw bad("observedAt");
		String status = option(body, "status", old == null ? "DRAFT" : old.status(), "DRAFT", "CONFIRMED", "RETRACTED");
		if (old == null && "RETRACTED".equals(status)) throw bad("status");
		return new ObservationFields(option(body, "category", old == null ? null : old.category(),
				"TEMPERAMENT", "ROUTINE", "PEOPLE", "DOGS", "PLAY", "CARE", "HEALTH", "OTHER"),
				text(body, "content", old == null ? null : old.content(), false, 4000), observed,
				text(body, "sourceNote", old == null ? null : old.sourceNote(), true, 1000), status);
	}

	static void version(JsonNode body, Instant current) {
		if (!instant(body, "expectedUpdatedAt").equals(current)) throw ManagementException.conflict("STALE_RESOURCE", "다른 수정 내용이 있어요. 다시 조회한 뒤 수정해 주세요.");
	}

	static UUID id(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		} catch (IllegalArgumentException | NullPointerException exception) { throw bad("ID"); }
	}

	static UUID shelterId(JsonNode body) { return id(text(body, "shelterId", null, false, 36)); }

	static int limit(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,2}") || Integer.parseInt(value) < 1 || Integer.parseInt(value) > 50) throw bad("limit (1~50)");
		return Integer.parseInt(value);
	}

	static UUID after(String cursor, String resource, UUID scope) {
		if (cursor == null) return null;
		try {
			if (cursor.isEmpty() || cursor.length() > 256 || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
			if (parts.length != 4 || !"1".equals(parts[0]) || !resource.equals(parts[1]) || !scope.toString().equals(parts[2])) throw new IllegalArgumentException();
			return id(parts[3]);
		} catch (IllegalArgumentException | ManagementException exception) {
			throw new ManagementException(400, "INVALID_CURSOR", "목록의 첫 페이지부터 다시 조회해 주세요.");
		}
	}
	static String cursor(String resource, UUID scope, UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(("1\n" + resource + "\n" + scope + "\n" + id).getBytes(StandardCharsets.UTF_8));
	}

	private static void object(JsonNode body, Set<String> allowed, String extra) {
		if (body == null || !body.isObject() || body.isEmpty()) throw ManagementException.invalid("입력할 항목을 JSON 객체로 보내 주세요.");
		for (var field : body.properties()) {
			if (!allowed.contains(field.getKey()) && !field.getKey().equals(extra)) throw ManagementException.invalid("지원하지 않는 입력 항목이 있어요.");
		}
		if ("expectedUpdatedAt".equals(extra) && body.size() < 2) throw ManagementException.invalid("수정할 항목을 함께 보내 주세요.");
	}
	private static String option(JsonNode body, String key, String fallback, String... options) {
		String value = text(body, key, fallback, false, 24);
		if (!Arrays.asList(options).contains(value)) throw bad(key);
		return value;
	}
	private static String text(JsonNode body, String key, String fallback, boolean nullable, int max) {
		if (!body.has(key)) { if (fallback == null && !nullable) throw bad(key); return fallback; }
		var value = body.get(key);
		if (value.isNull() && nullable) return null;
		if (!value.isString()) throw bad(key);
		return checkedText(value.asString(), key, max);
	}
	private static String checkedText(String value, String key, int max) {
		String stripped = value.strip();
		if (stripped.isBlank() || stripped.codePointCount(0, stripped.length()) > max || stripped.indexOf('\0') >= 0) throw bad(key);
		return stripped;
	}
	private static Boolean bool(JsonNode body, String key, Boolean fallback, boolean nullable) {
		if (!body.has(key)) return fallback;
		var value = body.get(key);
		if (value.isNull() && nullable) return null;
		if (!value.isBoolean()) throw bad(key);
		return value.booleanValue();
	}
	private static Instant instant(JsonNode body, String key) {
		try { return OffsetDateTime.parse(text(body, key, null, false, 40)).toInstant(); }
		catch (DateTimeParseException exception) { throw bad(key); }
	}
	private static ManagementException bad(String field) { return ManagementException.invalid(field + " 값을 확인해 주세요."); }
}
