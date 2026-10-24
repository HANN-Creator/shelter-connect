package org.shelterconnect.api;

import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ShelterMapPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    UUID shelter, reviewer;

    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void fixtures() {
        reviewer = UUID.randomUUID(); shelter = UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.app_users(id,display_name) VALUES (?,'맵 검증')", reviewer);
        jdbc.update("""
                INSERT INTO shelter.shelters(id,name,region,map_key,approval_status,is_public,reviewed_by,reviewed_at)
                VALUES (?,'맵 검증 보호소','테스트','legacy-map','APPROVED',true,?,now())
                """, shelter, reviewer);
    }

    @Test void everyShelterGetsThreeSharedMapsAndLegacyKeyIsPreserved() throws Exception {
        var response = mvc.perform(get("/v1/shelters/" + shelter + "/maps")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.length()").value(3)).andReturn().getResponse();
        var data = json.readTree(response.getContentAsString()).path("data");
        assertThat(data.valueStream().map(n -> n.path("title").asText()).toList())
                .containsExactly("햇살 운동장", "숲속 산책길", "물가 쉼터");
        assertThat(data.valueStream().filter(n -> n.path("isDefault").asBoolean()).count()).isEqualTo(1);
        assertThat(data.get(0).path("isDefault").asBoolean()).isTrue();
        mvc.perform(get("/v1/shelters/" + shelter)).andExpect(jsonPath("$.data.mapKey").value("legacy-map"));
        jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?", shelter);
        mvc.perform(get("/v1/shelters/" + shelter + "/maps")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.map_templates", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.shelter_maps WHERE shelter_id=?", Integer.class, shelter)).isEqualTo(3);
    }

    @Test void dbManifestLayoutAndEveryRuntimeFileMatchTheServedBytes() throws Exception {
        int count = 0;
        for (var row : jdbc.queryForList("SELECT id,version,manifest::text,layout::text,files::text FROM shelter.map_templates")) {
            String base = "/v1/map-assets/" + row.get("version") + "/" + row.get("id") + "/";
            var files = json.readTree((String) row.get("files"));
            for (var entry : files.properties()) {
                byte[] bytes = mvc.perform(get(base + entry.getKey())).andExpect(status().isOk())
                        .andExpect(content().contentType(entry.getValue().path("contentType").asText()))
                        .andReturn().getResponse().getContentAsByteArray();
                assertThat(bytes.length).isEqualTo(entry.getValue().path("bytes").asInt());
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .isEqualTo(entry.getValue().path("sha256").asText());
                if (entry.getKey().equals("manifest.json")) assertThat(json.readTree(bytes)).isEqualTo(json.readTree((String)row.get("manifest")));
                if (entry.getKey().equals("map/layout.json")) assertThat(json.readTree(bytes)).isEqualTo(json.readTree((String)row.get("layout")));
                count++;
            }
        }
        assertThat(count).isEqualTo(204);
    }

    @Test void runtimeRegistrationTriggerWorksWithoutGivingRuntimeMapWritePermissions() {
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        // This is exactly the column grant used by public animal registration.
        jdbc.update("INSERT INTO shelter.shelters(name,region,address,contact_phone,public_care_reg_no) VALUES ('새 보호소','테스트','주소','전화','999999999999999')");
        UUID added = jdbc.queryForObject("SELECT id FROM shelter.shelters WHERE public_care_reg_no='999999999999999'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.shelter_maps WHERE shelter_id=?", Integer.class, added)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT is_public FROM shelter.shelters WHERE id=?", Boolean.class, added)).isFalse();
        for (String table : new String[]{"map_templates", "shelter_maps"}) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT')", Boolean.class, "shelter." + table)).isTrue();
            for (String privilege : new String[]{"INSERT", "UPDATE", "DELETE"}) {
                assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "shelter." + table, privilege)).isFalse();
            }
        }
    }
}
