package org.shelterconnect.api.photo;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.shelterconnect.api.photo.PhotoTypes.*;

class SupabasePhotoStorageTest {
	private HttpServer server;
	private ExecutorService executor;
	private final JsonMapper json = JsonMapper.builder().build();
	private final UUID dog = UUID.randomUUID();
	private final AtomicInteger calls = new AtomicInteger();
	private String bucketBody = "{\"id\":\"dog-photos\",\"public\":false}", responseBody;
	private int status = 200;
	private volatile String posted, receivedKey, authorization, receivedMethod;
	private volatile boolean stall;
	private SupabasePhotoStorage storage;
	@BeforeEach void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
		server.createContext("/storage/v1/", exchange -> {
			calls.incrementAndGet();
			receivedKey = exchange.getRequestHeaders().getFirst("apikey");
			authorization = exchange.getRequestHeaders().getFirst("Authorization");
			boolean bucket = exchange.getRequestURI().getPath().contains("/bucket/");
			if (!bucket) { receivedMethod = exchange.getRequestMethod(); posted = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
			try {
				if (stall) Thread.sleep(2000);
				byte[] body = (bucket ? bucketBody : responseBody).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Location", "https://example.invalid/do-not-follow");
				exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
			} catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
			finally { exchange.close(); }
		});
		server.start();
		storage = new SupabasePhotoStorage(new PhotoStorageProperties(true, "sb_secret_test_only", "dog-photos", 1), json,
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), "http://127.0.0.1:" + server.getAddress().getPort() + "/storage/v1");
		responseBody = result(photo("one.jpg"), null);
	}
	@AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
	@Test void signsBatchWithSecretHeaderAndEncodesNamesWithoutChangingObjectIdentity() {
		var first = photo("산책 한 컷+1.jpg"); var second = photo("two.png");
		responseBody = json.writeValueAsString(List.of(resultEntry(second), resultEntry(first)));
		var before = Instant.now(); var links = storage.sign(List.of(first, second));
		assertThat(calls.get()).isEqualTo(2);
		assertThat(receivedKey).isEqualTo("sb_secret_test_only"); assertThat(authorization).isNull();
		assertThat(receivedMethod).isEqualTo("POST");
		var payload = json.readTree(posted);
		assertThat(payload.get("expiresIn").asInt()).isEqualTo(60);
		assertThat(payload.get("paths").get(0).asText()).isEqualTo(first.key());
		assertThat(links.get(first.id()).url()).contains("%20", "%2B").doesNotContain("sb_secret", " ");
		assertThat(URI.create(links.get(first.id()).url()).getPath()).endsWith("/" + first.key());
		assertThat(links.get(first.id()).expiresAt()).isBetween(before.plusSeconds(60), Instant.now().plusSeconds(60));
	}
	@Test void publicBucketAndMalformedBucketFailBeforeSigning() {
		for (String body : List.of("{\"id\":\"dog-photos\",\"public\":true}", "{\"id\":\"other\",\"public\":false}", "{}")) {
			bucketBody = body;
			assertThatThrownBy(() -> storage.sign(List.of(photo("one.jpg")))).isInstanceOfSatisfying(PhotoException.class, ex -> assertThat(ex.code()).isEqualTo("PHOTO_STORAGE_NOT_PRIVATE"));
		}
		assertThat(calls.get()).isEqualTo(3); assertThat(posted).isNull();
	}
	@ParameterizedTest @ValueSource(ints = {301, 401, 403, 404, 429, 500})
	void providerErrorsAreSanitizedAndNotRetried(int code) {
		status = code; bucketBody = "private provider details and sb_secret_test_only";
		assertThatThrownBy(() -> storage.sign(List.of(photo("one.jpg")))).isInstanceOfSatisfying(PhotoException.class, ex -> {
			assertThat(ex.code()).isEqualTo("PHOTO_STORAGE_UNAVAILABLE"); assertThat(ex.getMessage()).doesNotContain("private provider", "sb_secret"); assertThat(ex.getCause()).isNull();
		});
		assertThat(calls.get()).isEqualTo(1);
	}
	@Test void absentWrongDuplicatedOrExternalSignedUrlsNeverEscape() {
		var photo = photo("one.jpg");
		for (String body : List.of("[]", "not json", "null", result(photo, "not found"),
				json.writeValueAsString(List.of(Map.of("path", "other.jpg", "signedURL", "/object/sign/dog-photos/other.jpg?token=a"))),
				json.writeValueAsString(List.of(Map.of("path", photo.key(), "signedURL", "https://evil.invalid/file?token=a"))),
				json.writeValueAsString(List.of(Map.of("path", photo.key(), "signedURL", "/object/sign/dog-photos/" + photo.key() + "?token=a&redirect=evil"))),
				json.writeValueAsString(List.of(resultEntry(photo), resultEntry(photo))))) {
			responseBody = body;
			assertThatThrownBy(() -> storage.sign(List.of(photo))).isInstanceOf(PhotoException.class);
		}
	}
	@Test void responseSizeAndWholeRequestTimeAreBounded() {
		responseBody = "x".repeat(262145);
		assertThatThrownBy(() -> storage.sign(List.of(photo("one.jpg")))).isInstanceOf(PhotoException.class);
		stall = true; calls.set(0);
		long start = System.nanoTime();
		assertThatThrownBy(() -> storage.sign(List.of(photo("one.jpg")))).isInstanceOfSatisfying(PhotoException.class, ex -> assertThat(ex.code()).isEqualTo("PHOTO_STORAGE_TIMEOUT"));
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4)); assertThat(calls.get()).isEqualTo(1);
	}
	@Test void unsafeLocationsAndDisabledConfigurationMakeNoRequests() {
		for (String key : List.of("../one.jpg", "nested/../one.jpg", "nested//one.jpg", "one%2f.jpg", "one?.jpg", "one#.jpg", "one\\x.jpg", "one.svg"))
			assertThatThrownBy(() -> storage.sign(List.of(photo(key)))).isInstanceOf(PhotoException.class);
		var wrongDog = new Stored(UUID.randomUUID(), UUID.randomUUID(), "dog-photos", dog + "/one.jpg", 0, null, Instant.now());
		assertThatThrownBy(() -> storage.sign(List.of(wrongDog))).isInstanceOf(PhotoException.class);
		var wrongBucket = new Stored(UUID.randomUUID(), dog, "other", dog + "/one.jpg", 0, null, Instant.now());
		assertThatThrownBy(() -> storage.sign(List.of(wrongBucket))).isInstanceOf(PhotoException.class);
		assertThat(calls.get()).isZero();
		var disabled = new SupabasePhotoStorage(new PhotoStorageProperties(false, "", "dog-photos", 1), json, HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
		assertThat(disabled.sign(List.of())).isEmpty();
		assertThatThrownBy(() -> disabled.sign(List.of(photo("one.jpg")))).isInstanceOfSatisfying(PhotoException.class, ex -> assertThat(ex.code()).isEqualTo("PHOTO_STORAGE_NOT_CONFIGURED"));
		assertThat(calls.get()).isZero();
	}
	@Test void invalidConfigFailsWithoutPrintingSecrets() {
		assertThatThrownBy(() -> new PhotoStorageProperties(true, "sb_publishable_private-value", "dog-photos", 5)).hasMessageNotContaining("private-value");
		assertThatThrownBy(() -> new PhotoStorageProperties(true, "sb_secret_dummy", "../other", 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PhotoStorageProperties(false, "", "dog-photos", 11)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new PhotoStorageProperties(true, "sb_secret_dummy", "dog-photos", 5).toString()).doesNotContain("sb_secret_dummy");
	}
	private Stored photo(String name) { return new Stored(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), dog, "dog-photos", dog + "/" + name, 0, null, Instant.now()); }
	private Map<String, Object> resultEntry(Stored photo) { return Map.of("path", photo.key(), "signedURL", "/object/sign/dog-photos/" + photo.key() + "?token=test.signature"); }
	private String result(Stored photo, String error) { var entry = new HashMap<>(resultEntry(photo)); entry.put("error", error); return json.writeValueAsString(List.of(entry)); }
}
