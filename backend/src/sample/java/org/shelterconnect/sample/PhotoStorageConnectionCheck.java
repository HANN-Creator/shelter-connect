package org.shelterconnect.sample;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.shelterconnect.api.auth.SupabaseProperties;
import org.shelterconnect.api.photo.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Manual development check, excluded from the API JAR. No app records or permissions are changed. */
public final class PhotoStorageConnectionCheck {
	private static final UUID FIXTURE_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");
	private static final String FIXTURE_KEY = FIXTURE_ID + "/connection-check.png";
	private static final long FILE_LIMIT = 5 * 1024 * 1024;
	private static final Set<String> MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
	private final JsonMapper json = JsonMapper.builder().build();
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private final String project, base, secret, bucket;
	private final int timeout;

	private PhotoStorageConnectionCheck(Map<String, String> env) {
		project = env.getOrDefault("SUPABASE_URL", "");
		if (!project.matches("https://[a-z0-9]{20}\\.supabase\\.co")) throw new IllegalArgumentException();
		secret = env.getOrDefault("SUPABASE_SECRET_KEY", "");
		bucket = env.getOrDefault("PHOTO_STORAGE_BUCKET", "dog-photos");
		timeout = Integer.parseInt(env.getOrDefault("PHOTO_STORAGE_TIMEOUT_SECONDS", "5"));
		new PhotoStorageProperties(true, secret, bucket, timeout);
		base = project + "/storage/v1";
	}

	public static void main(String[] args) {
		try {
			if (args.length > 1 || args.length == 1 && !args[0].equals("--prepare")) throw new IllegalArgumentException();
			new PhotoStorageConnectionCheck(System.getenv()).check(args.length == 1);
		} catch (PhotoException failure) {
			System.err.println("Storage adapter failed: " + failure.code());
			System.exit(1);
		} catch (CheckFailure failure) {
			System.err.println(failure.getMessage()); // Only our constant diagnostics, never response bodies or URLs.
			System.exit(1);
		} catch (Exception failure) {
			System.err.println("Storage check failed. Check the local project/key, Java 21 and network; no secrets were printed.");
			System.exit(1);
		}
	}

	private void check(boolean prepare) throws Exception {
		var listResponse = request("GET", base + "/bucket", null, true, null);
		require(listResponse.statusCode() == 200, "Cannot list Storage buckets with this server key.");
		JsonNode buckets = json.readTree(listResponse.body());
		require(buckets.isArray(), "Unexpected bucket response.");
		boolean exists = false;
		for (var item : buckets) if (bucket.equals(item.path("id").asText())) exists = true;
		if (!exists) {
			require(prepare, "Private bucket is missing. Use --prepare once to create it.");
			var response = request("POST", base + "/bucket", json.writeValueAsBytes(Map.of(
					"id", bucket, "name", bucket, "public", false, "file_size_limit", FILE_LIMIT,
					"allowed_mime_types", MIME_TYPES)), true, "application/json");
			require(response.statusCode() == 200, "Bucket creation failed; no existing bucket was modified.");
			System.out.println("Created private image bucket (5 MiB, JPEG/PNG/WebP/GIF).");
		}
		var bucketResponse = request("GET", base + "/bucket/" + bucket, null, true, null);
		require(bucketResponse.statusCode() == 200, "Cannot read bucket settings.");
		verifyBucket(json.readTree(bucketResponse.body()), bucket);
		System.out.println("Private bucket and upload restrictions verified.");

		byte[] fixture = fixture();
		if (prepare) {
			var upload = request("POST", base + "/object/" + bucket + "/" + FIXTURE_KEY, fixture, true, "image/png");
			boolean duplicate = upload.statusCode() == 409;
			if (upload.statusCode() == 400) duplicate = json.readTree(upload.body()).path("statusCode").asText().equals("409");
			require(upload.statusCode() == 200 || duplicate, "Fixture upload failed; existing files were not overwritten.");
			System.out.println(duplicate ? "Dedicated fixture already exists; checking its bytes." : "Uploaded dedicated test PNG (not a dog photo).");
		}
		var storage = new SupabasePhotoStorage(new PhotoStorageProperties(true, secret, bucket, timeout),
				new SupabaseProperties(project), json);
		var photo = new PhotoTypes.Stored(FIXTURE_ID, FIXTURE_ID, bucket, FIXTURE_KEY, 0,
				"Storage connection test only", Instant.EPOCH);
		var started = Instant.now();
		var signed = storage.sign(List.of(photo)).get(FIXTURE_ID);
		require(signed != null && !signed.expiresAt().isBefore(started.plusSeconds(60)), "Signed URL expiry is invalid.");
		var download = request("GET", signed.url(), null, false, null);
		require(download.statusCode() == 200 && Arrays.equals(fixture, download.body()), "Signed download did not match the test PNG.");
		require(download.headers().firstValue("content-type").orElse("").startsWith("image/png"), "Downloaded file is not image/png.");
		System.out.println("Production adapter: 60-second signed URL and exact PNG download passed.");
		for (String path : List.of("/object/public/", "/object/authenticated/")) {
			var response = request("GET", base + path + bucket + "/" + FIXTURE_KEY, null, false, null);
			require(Set.of(400, 401, 403, 404).contains(response.statusCode()), "Unauthenticated image access was not denied.");
		}
		System.out.println("Public and unauthenticated direct download denied. No app data, auth permissions, or Storage policies changed.");
	}

	static void verifyBucket(JsonNode info, String bucket) {
		require(bucket.equals(info.path("id").asText()) && info.path("public").isBoolean()
				&& !info.path("public").asBoolean(), "Bucket must be private; existing settings were not changed.");
		require(info.path("file_size_limit").asLong() == FILE_LIMIT, "Bucket file limit must be 5 MiB; existing settings were not changed.");
		var types = new HashSet<String>();
		for (var type : info.path("allowed_mime_types")) types.add(type.asText());
		require(types.equals(MIME_TYPES), "Bucket MIME restrictions differ; existing settings were not changed.");
	}

	private HttpResponse<byte[]> request(String method, String url, byte[] body, boolean authenticated, String mime) throws Exception {
		// Even signed downloads stay on the selected project, and redirects are never followed.
		require(url.startsWith(base + "/"), "Refusing a request outside the configured Storage origin.");
		var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout));
		if (authenticated) request.header("apikey", secret);
		if (mime != null) request.header("Content-Type", mime);
		if (method.equals("POST") && mime != null && mime.startsWith("image/")) request.header("x-upsert", "false");
		request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
		var pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		try { return pending.get(timeout + 1L, TimeUnit.SECONDS); }
		finally { if (!pending.isDone()) pending.cancel(true); }
	}

	static byte[] fixture() throws Exception {
		var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
			image.setRGB(x, y, (x + y) % 2 == 0 ? 0x77AA99 : 0xFFDD88);
		var bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new CheckFailure(message);
	}
	private static final class CheckFailure extends RuntimeException {
		CheckFailure(String message) { super(message); }
	}
}
