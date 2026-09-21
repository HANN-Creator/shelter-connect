package org.shelterconnect.sample;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PhotoStorageConnectionCheckTest {
	private final JsonMapper json = JsonMapper.builder().build();
	private static final String PRIVATE = """
		{"id":"dog-photos","public":false,"file_size_limit":5242880,
		 "allowed_mime_types":["image/jpeg","image/png","image/webp","image/gif"]}
		""";

	@Test void existingBucketMustHavePrivateImageOnlyLimits() {
		PhotoStorageConnectionCheck.verifyBucket(json.readTree(PRIVATE), "dog-photos");
		for (String invalid : new String[] {
				PRIVATE.replace("false", "true"), PRIVATE.replace("5242880", "null"),
				PRIVATE.replace("image/png", "image/*"), PRIVATE.replace("dog-photos", "other")}) {
			assertThatThrownBy(() -> PhotoStorageConnectionCheck.verifyBucket(json.readTree(invalid), "dog-photos"))
					.isInstanceOf(RuntimeException.class).hasMessageContaining("not changed");
		}
	}

	@Test void fixtureIsSmallDeterministicPngWithoutExternalPhotoData() throws Exception {
		byte[] fixture = PhotoStorageConnectionCheck.fixture();
		assertThat(fixture).isEqualTo(PhotoStorageConnectionCheck.fixture()).hasSizeLessThan(1024);
		var image = ImageIO.read(new ByteArrayInputStream(fixture));
		assertThat(image.getWidth()).isEqualTo(8);
		assertThat(image.getHeight()).isEqualTo(8);
	}
}
