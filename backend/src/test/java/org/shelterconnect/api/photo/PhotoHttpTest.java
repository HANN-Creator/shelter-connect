package org.shelterconnect.api.photo;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.web.ApiRequestFilter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PhotoController.class)
@Import({PhotoService.class, PhotoErrorHandler.class, ApiRequestFilter.class,
		SecurityConfiguration.class, SecurityErrors.class, JwtTestConfiguration.class})
class PhotoHttpTest {
	@Autowired MockMvc mvc;
	@Autowired JwtTestSupport tokens;
	@MockitoBean PhotoStore store;
	@MockitoBean PhotoStorage storage;
	private final UUID subject = UUID.randomUUID(), dog = UUID.randomUUID();
	private String path() { return "/v1/dogs/" + dog + "/photos"; }
	@Test void loginAndOnlyGetAreRequired() throws Exception {
		mvc.perform(get(path())).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get(path()).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
		mvc.perform(post(path()).header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isForbidden());
		mvc.perform(head(path()).header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isForbidden());
		verifyNoInteractions(store, storage);
	}
	@Test void invalidInputsNeverReachStorage() throws Exception {
		for (String limit : List.of("0", "51", "-1", "x", "1.5", ""))
			mvc.perform(get(path()).param("limit", limit).header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isBadRequest());
		mvc.perform(get("/v1/dogs/not-a-uuid/photos").header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isBadRequest());
		verifyNoInteractions(store, storage);
	}
	@Test void lockedResponseIsActionableAndEmptyPhotosNeverCallStorage() throws Exception {
		when(store.load(subject, dog, null, 20)).thenThrow(new PhotoException(403, "PHOTO_LOCKED", "먼저 이야기를 나눠 주세요."));
		mvc.perform(get(path()).header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PHOTO_LOCKED")).andExpect(jsonPath("$.requestId").isNotEmpty());
		doReturn(new PhotoTypes.Snapshot(subject, UUID.randomUUID(), dog, List.of(), null)).when(store).load(subject, dog, null, 20);
		mvc.perform(get(path()).header("Authorization", "Bearer " + tokens.token(subject))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.nextCursor").doesNotExist());
		verifyNoInteractions(storage);
	}
}
