package org.shelterconnect.api.catalog;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.SecurityConfiguration;
import org.shelterconnect.api.auth.SecurityErrors;
import org.shelterconnect.api.web.ApiRequestFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShelterMapController.class)
@Import({MapResourceStore.class, CatalogErrorHandler.class, ApiRequestFilter.class,
        SecurityConfiguration.class, SecurityErrors.class})
class ShelterMapHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean CatalogRepository catalog;
    @MockitoBean ShelterMapRepository maps;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test void meetingSceneLookupIsPublicReadOnlyAndRejectsMalformedKeysBeforeDb() throws Exception {
        String path = "/v1/meeting-scenes/dog-meeting-courtyard/versions/1.0.0";
        when(maps.meetingScene("dog-meeting-courtyard", "1.0.0")).thenReturn(Optional.empty());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        verify(maps).meetingScene("dog-meeting-courtyard", "1.0.0");
        mvc.perform(get("/v1/meeting-scenes/INVALID/versions/1.0.0")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/meeting-scenes/dog-meeting-courtyard/versions/latest")).andExpect(status().isNotFound());
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(maps);
        verifyNoInteractions(catalog);
    }

    @Test void publicFilesWorkWithoutLoginAndHaveImmutableCaching() throws Exception {
        String path = "/v1/map-assets/1.0.0/01-sunny-meadow/map/ground.png";
        var response = mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().exists("ETag")).andReturn().getResponse();
        mvc.perform(get(path).header("If-None-Match", response.getHeader("ETag")))
                .andExpect(status().isNotModified());
        mvc.perform(head(path)).andExpect(status().isOk());
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog, maps);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0/01-sunny-meadow/source.py", "1.0.0/01-sunny-meadow/preview.html",
            "1.0.0/01-sunny-meadow/map/missing.png", "9.9.9/01-sunny-meadow/map/ground.png",
            "1.0.0/missing/map/ground.png", "1.0.0/01-sunny-meadow/application.properties"})
    void unknownFilesDoNotExposeClasspathResources(String path) throws Exception {
        mvc.perform(get("/v1/map-assets/" + path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test void privateSheltersAreNotExposedAndInvalidIdsNeverReachDb() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.shelter(id)).thenReturn(Optional.empty());
        mvc.perform(get("/v1/shelters/" + id + "/maps")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/shelters/not-a-uuid/maps")).andExpect(status().isBadRequest());
        verify(catalog).shelter(id);
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(maps);
    }

    @Test void traversalIsRejectedBeforeResourceLookup() throws Exception {
        mvc.perform(get("/v1/map-assets/1.0.0/01-sunny-meadow/../application.properties"))
                .andExpect(status().isBadRequest());
    }
}
