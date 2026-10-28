package org.shelterconnect.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.storage.LocalObjectStore;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.storage.backend=local")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class LocalStorageApiDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean LocalObjectStore objectStore;

    @Test void conditionalLocalStorageRouteDoesNotBreakJsonOrYamlDocumentation() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/v1/storage/objects/{objectId}'].get").exists())
            .andExpect(jsonPath("$.paths['/v1/meeting-scenes/{sceneId}'].get").exists())
            .andReturn().getResponse();
        var root=json.readTree(response.getContentAsString());
        var operation=root.path("paths").path("/v1/storage/objects/{objectId}").path("get");
        assertThat(operation.path("security").size()).isZero();
        var names=new java.util.HashSet<String>();
        for(var parameter:operation.path("parameters")) {
            names.add(parameter.path("name").asText());
            assertThat(parameter.path("required").asBoolean()).isTrue();
            if(parameter.path("name").asText().equals("objectId"))
                assertThat(parameter.at("/schema/format").asText()).isNotEqualTo("uuid");
        }
        assertThat(names).containsExactlyInAnyOrder("objectId","expires","signature");
        assertThat(operation.at("/responses/200/content/image~1png/schema/$ref").asText()).endsWith("StorageObjectFile");
        assertThat(operation.path("responses").propertyNames()).contains("403","404","503");
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    @Test void documentationDoesNotOpenUnsignedObjectsOrProtectedRoutes() throws Exception {
        String id="a".repeat(64);
        when(objectStore.readSigned(id,1L,"invalid")).thenThrow(new IllegalArgumentException("Invalid storage link"));
        mvc.perform(get("/v1/storage/objects/"+id)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/storage/objects/"+id).param("expires","1").param("signature","invalid"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/v1/storage/objects/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
    }
}
