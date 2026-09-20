package org.shelterconnect.api.photo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

final class PhotoQuery {
	private PhotoQuery() {}
	record Cursor(int order, UUID id) {}
	static UUID id(String value) {
		if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw PhotoException.invalid();
		return UUID.fromString(value);
	}
	static int limit(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,2}")) throw PhotoException.invalid();
		int size = Integer.parseInt(value);
		if (size < 1 || size > 50) throw PhotoException.invalid();
		return size;
	}
	static Cursor after(String value, UUID user, UUID dog) {
		if (value == null) return null;
		if (value.length() > 512 || !value.matches("[A-Za-z0-9_-]+")) throw PhotoException.cursor();
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\n", -1);
			if (parts.length != 6 || !parts[0].equals("1") || !parts[1].equals("photos")
					|| !parts[2].equals(user.toString()) || !parts[3].equals(dog.toString()) || !parts[4].matches("[0-9]{1,10}")) throw PhotoException.cursor();
			int order = Integer.parseInt(parts[4]);
			return new Cursor(order, id(parts[5]));
		} catch (RuntimeException ex) { throw PhotoException.cursor(); }
	}
	static String cursor(UUID user, UUID dog, PhotoTypes.Stored photo) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				("1\nphotos\n" + user + "\n" + dog + "\n" + photo.sortOrder() + "\n" + photo.id()).getBytes(StandardCharsets.UTF_8));
	}
}
