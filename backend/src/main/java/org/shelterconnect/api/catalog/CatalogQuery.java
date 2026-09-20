package org.shelterconnect.api.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

final class CatalogQuery {
	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private CatalogQuery() {}

	static UUID id(String value) {
		if (value == null || !UUID_FORMAT.matcher(value).matches()) {
			throw CatalogException.invalid("ID는 UUID 형식으로 보내 주세요.");
		}
		return UUID.fromString(value);
	}

	static int limit(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,2}")) throw invalidLimit();
		int limit = Integer.parseInt(value);
		if (limit < 1 || limit > 50) throw invalidLimit();
		return limit;
	}

	static String region(String value) {
		if (value == null) return "";
		if (value.length() > 100 || value.chars().anyMatch(Character::isISOControl)) {
			throw CatalogException.invalid("지역은 100자 이내로 보내 주세요.");
		}
		return value.strip();
	}

	// A cursor is a position, not an access token. Every query still applies visibility rules.
	static UUID after(String cursor, String resource, String scope) {
		if (cursor == null) return null;
		if (cursor.isEmpty() || cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+")) {
			throw CatalogException.invalidCursor();
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\n", -1);
			if (parts.length != 4 || !"1".equals(parts[0]) || !resource.equals(parts[1])
					|| !scope.equals(parts[2]) || !UUID_FORMAT.matcher(parts[3]).matches()) {
				throw CatalogException.invalidCursor();
			}
			return UUID.fromString(parts[3]);
		} catch (IllegalArgumentException exception) {
			throw CatalogException.invalidCursor();
		}
	}

	static String cursor(String resource, String scope, UUID after) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				("1\n" + resource + "\n" + scope + "\n" + after).getBytes(StandardCharsets.UTF_8));
	}

	private static CatalogException invalidLimit() {
		return CatalogException.invalid("limit은 1부터 50까지의 정수로 보내 주세요.");
	}
}
