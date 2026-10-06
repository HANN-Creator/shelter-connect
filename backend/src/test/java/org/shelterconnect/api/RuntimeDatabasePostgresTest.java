package org.shelterconnect.api;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.chat.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest(properties={"app.ai.enabled=true", "app.ai.api-key=test-only"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class RuntimeDatabasePostgresTest {
    private static final String RUNTIME_PASSWORD=UUID.randomUUID().toString();
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JwtTestSupport tokens; @Autowired JsonMapper json;
    @MockitoBean org.shelterconnect.api.asset.StyledQualityAgent spriteQuality;
    @MockitoBean org.shelterconnect.api.asset.StyledSeedQualityAgent seedQuality;
    @MockitoBean org.shelterconnect.api.asset.StyledLessonAgent spriteLessons;
    @MockitoBean AiProvider provider;
    @MockitoBean org.shelterconnect.api.asset.PhotoAppearanceProvider appearance;
    @MockitoBean org.shelterconnect.api.behavior.BehaviorSuggestionProvider suggestions;
    private JdbcTemplate admin;
    private UUID subject,user,shelter,dog,observation;
    @DynamicPropertySource static void beforeContext(DynamicPropertyRegistry registry) throws Exception {
        SchemaMigrationTest.migratePostgres();
        try(var connection=DriverManager.getConnection(System.getenv("TEST_DB_URL"),System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD")); var statement=connection.createStatement()) {
            // Local disposable database only, validated by migratePostgres().
            statement.execute("ALTER ROLE shelter_runtime LOGIN PASSWORD '"+RUNTIME_PASSWORD+"'");
        }
        registry.add("spring.datasource.username",()->"shelter_runtime");
        registry.add("spring.datasource.password",()->RUNTIME_PASSWORD);
    }
    @BeforeEach void fixtures() {
        admin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DB_URL"),System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD")));
        subject=UUID.randomUUID();user=UUID.randomUUID();shelter=UUID.randomUUID();dog=UUID.randomUUID();observation=UUID.randomUUID();
        admin.update("INSERT INTO shelter.app_users(id,display_name) VALUES (?,'검증 관리자')",user);
        admin.update("INSERT INTO shelter.shelters(id,name,region,approval_status,is_public,reviewed_by,reviewed_at) VALUES (?,'임시 보호소','테스트','APPROVED',true,?,now())",shelter,user);
        admin.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,status) VALUES (?,?,'ACTIVE')",user,shelter);
        admin.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'테스트','test',true,'AVAILABLE')",dog,shelter);
        admin.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at) VALUES (?,?,'PLAY','공을 좋아해요',now(),?,'CONFIRMED',?,now())",observation,dog,user,user);
        when(provider.generate(any())).thenReturn(new AiTypes.Generated("나는 공을 좋아해!",false,List.of(observation),"test-runtime"));
        assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo("shelter_runtime");
    }
    @AfterEach void cleanup() {
        admin.update("DELETE FROM shelter.adoption_notes WHERE dog_id=?",dog);
        admin.update("DELETE FROM shelter.chat_message_observations WHERE dog_id=?",dog);
        admin.update("DELETE FROM shelter.chat_messages WHERE dog_id=?",dog);
        admin.update("DELETE FROM shelter.chat_sessions WHERE dog_id=?",dog);
        admin.update("DELETE FROM shelter.dog_observations WHERE dog_id=?",dog);
        admin.update("DELETE FROM shelter.dogs WHERE id=?",dog);
        admin.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",shelter);
        admin.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
        admin.update("DELETE FROM shelter.app_users WHERE id=? OR auth_subject=?",user,subject.toString());
    }
    @Test void runtimeCanRegisterChatGenerateAndSaveNotesWithoutAdministrativeRights() throws Exception {
        mvc.perform(auth(post("/v1/me"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));
        mvc.perform(auth(post("/v1/me"))).andExpect(status().isOk());
        mvc.perform(auth(get("/v1/me"))).andExpect(status().isOk());
        mvc.perform(get("/v1/dogs/"+dog)).andExpect(status().isOk());
        String session=id(auth(post("/v1/dogs/"+dog+"/chat-sessions")),201);
        String message=id(auth(post("/v1/chat-sessions/"+session+"/messages").contentType("application/json")
            .content("{\"clientMessageId\":\"runtime-test\",\"text\":\"무슨 놀이를 좋아해?\"}")),201);
        mvc.perform(auth(post("/v1/chat-sessions/"+session+"/messages/"+message+"/reply")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.processingStatus").value("COMPLETED"));
        mvc.perform(auth(put("/v1/me/adoption-notes/"+dog).contentType("application/json")
            .content("{\"questions\":\"돌봄 질문\",\"carePlan\":\"매일 산책\",\"checklist\":{},\"expectedUpdatedAt\":null}"))).andExpect(status().isCreated());
        mvc.perform(auth(get("/v1/me/adoption-notes/"+dog))).andExpect(status().isOk()).andExpect(jsonPath("$.data.carePlan").value("매일 산책"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.ai_reply_usage WHERE request_message_id=? AND released_at IS NOT NULL",Integer.class,UUID.fromString(message))).isEqualTo(1);
    }
    @Test void runtimeCannotAlterAuthoritySchemaOrUsageHistory() throws Exception {
        for(String sql:List.of(
            "CREATE TABLE shelter.runtime_forbidden(id int)",
            "CREATE ROLE runtime_forbidden", "SET ROLE shelter_ci",
            "ALTER TABLE shelter.dogs DISABLE ROW LEVEL SECURITY",
            "UPDATE shelter.app_users SET role='OPERATOR' WHERE id='"+user+"'",
            "UPDATE shelter.app_users SET disabled_at=NULL WHERE id='"+user+"'",
            "UPDATE shelter.shelters SET approval_status='APPROVED' WHERE id='"+shelter+"'",
            "UPDATE shelter.shelter_memberships SET role='MANAGER'",
            "DELETE FROM shelter.ai_reply_usage", "UPDATE shelter.ai_reply_usage SET reserved_at=now()",
            "SELECT * FROM shelter.flyway_schema_history", "TRUNCATE shelter.chat_messages")) {
            assertThatThrownBy(()->jdbc.execute(sql)).as(sql).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR rolreplication FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
    }
    @Test void permissionLocksStillSeeProtectedRows() {
        assertThat(jdbc.queryForObject("SELECT id FROM shelter.app_users WHERE id=? FOR SHARE",UUID.class,user)).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT id FROM shelter.shelters WHERE id=? FOR SHARE",UUID.class,shelter)).isEqualTo(shelter);
        assertThat(jdbc.queryForObject("SELECT user_id FROM shelter.shelter_memberships WHERE user_id=? FOR SHARE",UUID.class,user)).isEqualTo(user);
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) { return b.header("Authorization","Bearer "+tokens.token(subject)); }
    private String id(MockHttpServletRequestBuilder b,int statusCode) throws Exception {
        return json.readTree(mvc.perform(b).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }
}
