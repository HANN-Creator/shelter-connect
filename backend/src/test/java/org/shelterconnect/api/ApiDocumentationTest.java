package org.shelterconnect.api;

import java.util.*;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class ApiDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test void everyRuntimeApiHasAnAccurateDocumentedRouteAndNoJwtParameter() throws Exception {
        var root=spec();var documented=new TreeSet<String>();
        root.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            var op=method.getValue();documented.add(method.getKey().toUpperCase()+" "+path.getKey());
            assertThat(op.path("summary").asText()).isNotBlank();
            assertThat(op.path("tags").size()).isEqualTo(1);
            assertThat(op.path("operationId").asText()).isNotBlank();
            for(var p:op.path("parameters")) assertThat(p.path("name").asText()).isNotEqualTo("jwt");
            assertThat(op.path("responses").has("500")).isTrue();
        }));
        var actual=new TreeSet<String>();
        requestMappingHandlerMapping.getHandlerMethods().forEach((mapping,handler) -> {
            for(String path:mapping.getPatternValues()) if(path.startsWith("/v1/"))
                for(var method:mapping.getMethodsCondition().getMethods()) actual.add(method+" "+path);
        });
        assertThat(documented).containsExactlyElementsOf(actual).hasSize(46);
        assertThat(root.at("/servers/0/url").asText()).isEqualTo("/");
        assertThat(root.at("/components/securitySchemes/supabaseBearer/scheme").asText()).isEqualTo("bearer");
        assertThat(root.path("paths").path("/v1/dogs/{dogId}/assets").path("get").path("security").size()).isZero();
        assertThat(root.path("paths").path("/v1/dogs/{dogId}/photos").path("get").path("security").get(0).has("supabaseBearer")).isTrue();
        checkReferences(root,root);
    }
    @Test void requestsAndMapResponsesHaveUsefulSchemasIncludingMultipartAndDistinctJobs() throws Exception {
        var root=spec();var schemas=root.at("/components/schemas");
        assertThat(schemas.path("AssetJob").path("properties").has("actionPlan")).isTrue();
        assertThat(schemas.path("BehaviorSuggestionJob").path("properties").has("result")).isTrue();
        assertThat(schemas.path("BehaviorProfile").at("/properties/settings/$ref").asText()).endsWith("BehaviorSettingsInput");
        assertThat(schemas.path("BehaviorSaveInput").path("required").size()).isEqualTo(5);
        var upload=root.path("paths").path("/v1/shelter-admin/dogs/{dogId}/photos").path("post");
        assertThat(upload.at("/requestBody/content/multipart~1form-data/encoding/metadata/contentType").asText()).isEqualTo("application/json");
        assertThat(schemas.path("PhotoUploadInput").at("/properties/file/format").asText()).isEqualTo("binary");
        var manifest=root.path("paths").path("/v1/dogs/{dogId}/assets").path("get");
        assertThat(manifest.at("/responses/200/content/application~1json/schema/$ref").asText()).endsWith("AssetManifestResponse");
        assertThat(schemas.path("AssetManifest").path("properties").has("animations")).isTrue();
        assertThat(root.path("paths").path("/v1/chat-sessions/{sessionId}/messages").path("post").path("responses").has("201")).isTrue();
    }
    @Test void documentationIsReadableWithoutOpeningProtectedApiRoutesOrPersistingTokens() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.persistAuthorization").value(false));
        mvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/shelter-admin/dogs").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
    private JsonNode spec() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }
    private void checkReferences(JsonNode node,JsonNode root) {
        if(node.isObject()) node.properties().forEach(e -> {
            if(e.getKey().equals("$ref") && e.getValue().asText().startsWith("#/"))
                assertThat(root.at(e.getValue().asText().substring(1)).isMissingNode()).as(e.getValue().asText()).isFalse();
            else checkReferences(e.getValue(),root);
        });
        else if(node.isArray()) node.forEach(n -> checkReferences(n,root));
    }
}
