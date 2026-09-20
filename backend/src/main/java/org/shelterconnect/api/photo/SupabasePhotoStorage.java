package org.shelterconnect.api.photo;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.auth.SupabaseProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.photo.PhotoTypes.*;

@Component
public class SupabasePhotoStorage implements PhotoStorage {
	private final PhotoStorageProperties properties;
	private final JsonMapper json;
	private final HttpClient client;
	private final String base;
	@Autowired
	public SupabasePhotoStorage(PhotoStorageProperties properties, SupabaseProperties auth, JsonMapper json) {
		this(properties, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
				.followRedirects(HttpClient.Redirect.NEVER).build(), auth.supabaseUrl() + "/storage/v1");
	}
	// Only tests may inject a loopback endpoint. Production always uses the configured Auth project.
	SupabasePhotoStorage(PhotoStorageProperties properties, JsonMapper json, HttpClient client, String base) {
		this.properties = properties; this.json = json; this.client = client; this.base = base;
	}
	@Override public Map<UUID, Signed> sign(List<Stored> photos) {
		if (photos.isEmpty()) return Map.of();
		if (!properties.enabled()) throw new PhotoException(503, "PHOTO_STORAGE_NOT_CONFIGURED", "사진 서비스를 준비 중이에요.");
		if (photos.size() > 50) throw PhotoException.unavailable();
		var byPath = new HashMap<String, Stored>();
		for (var photo : photos) {
			validateLocation(photo);
			if (byPath.put(photo.key(), photo) != null) throw PhotoException.unavailable();
		}
		JsonNode bucket = request("/bucket/" + properties.bucket(), null);
		if (!bucket.path("id").asText().equals(properties.bucket()) || !bucket.path("public").isBoolean() || bucket.get("public").asBoolean())
			throw new PhotoException(503, "PHOTO_STORAGE_NOT_PRIVATE", "사진 저장소 설정을 확인하고 있어요.");
		Instant expiresAt = Instant.now().plusSeconds(PhotoStorageProperties.URL_SECONDS);
		JsonNode results = request("/object/sign/" + properties.bucket(), json.writeValueAsString(Map.of(
				"expiresIn", PhotoStorageProperties.URL_SECONDS, "paths", photos.stream().map(Stored::key).toList())));
		if (!results.isArray() || results.size() != photos.size()) throw PhotoException.unavailable();
		var signed = new HashMap<UUID, Signed>();
		for (var result : results) {
			Stored photo = byPath.get(result.path("path").asText());
			if (photo == null || (result.has("error") && !result.get("error").isNull()) || !result.path("signedURL").isString()) throw PhotoException.unavailable();
			String url = validatedUrl(result.get("signedURL").asText(), photo);
			if (signed.put(photo.id(), new Signed(url, expiresAt)) != null) throw PhotoException.unavailable();
		}
		return Map.copyOf(signed);
	}
	private void validateLocation(Stored photo) {
		String key = photo.key();
		if (!properties.bucket().equals(photo.bucket()) || key == null || key.length() > 1024
				|| !key.startsWith(photo.dogId() + "/") || key.chars().anyMatch(Character::isISOControl)
				|| key.matches(".*[\\\\%?#].*") || !key.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)")) throw invalidLocation();
		for (String part : key.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalidLocation();
	}
	private PhotoException invalidLocation() {
		return new PhotoException(503, "PHOTO_STORAGE_INVALID", "사진 파일 정보를 확인하고 있어요.");
	}
	private String validatedUrl(String value, Stored photo) {
		try {
			if (value.length() > 8192) throw PhotoException.unavailable();
			int queryAt = value.indexOf('?');
			if (queryAt < 0) throw PhotoException.unavailable();
			String path = URLDecoder.decode(value.substring(0, queryAt).replace("+", "%2B"), StandardCharsets.UTF_8);
			String expected = "/object/sign/" + properties.bucket() + "/" + photo.key();
			String query = value.substring(queryAt + 1);
			if (!path.equals(expected) || !query.matches("token=[A-Za-z0-9_.-]+")) throw PhotoException.unavailable();
			String encoded = Arrays.stream(expected.split("/", -1))
					.map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"))
					.collect(java.util.stream.Collectors.joining("/"));
			return base + encoded + "?" + query;
		} catch (IllegalArgumentException ex) { throw PhotoException.unavailable(); }
	}
	private JsonNode request(String path, String body) {
		var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(properties.timeoutSeconds()))
				.header("apikey", properties.secretKey()).header("Accept", "application/json");
		if (body == null) builder.GET();
		else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		var future = client.sendAsync(builder.build(), info -> new LimitedBody());
		try {
			var response = future.get(properties.timeoutSeconds(), TimeUnit.SECONDS);
			if (response.statusCode() != 200) throw PhotoException.unavailable();
			try { return json.readTree(response.body()); }
			catch (RuntimeException ex) { throw PhotoException.unavailable(); }
		} catch (TimeoutException ex) {
			future.cancel(true); throw new PhotoException(504, "PHOTO_STORAGE_TIMEOUT", "사진을 불러오는 데 시간이 걸리고 있어요. 다시 시도해 주세요.");
		} catch (InterruptedException ex) {
			future.cancel(true); Thread.currentThread().interrupt(); throw PhotoException.unavailable();
		} catch (ExecutionException ex) {
			if (ex.getCause() instanceof HttpTimeoutException) throw new PhotoException(504, "PHOTO_STORAGE_TIMEOUT", "사진을 불러오는 데 시간이 걸리고 있어요. 다시 시도해 주세요.");
			throw PhotoException.unavailable();
		}
	}
	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription subscription;
		private long size;
		public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
		public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
		public void onNext(List<ByteBuffer> items) {
			for (var item : items) size += item.remaining();
			if (size > 262144) { subscription.cancel(); delegate.onError(PhotoException.unavailable()); }
			else delegate.onNext(items);
		}
		public void onError(Throwable failure) { delegate.onError(failure); }
		public void onComplete() { delegate.onComplete(); }
	}
}
