package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class StyledLessonPromptStore {
    public record Entry(String key,String state,JsonNode draft,JsonNode validation,boolean created) {}
    private final JdbcClient jdbc;private final JsonMapper json;private final StyledLessonStore lessons;
    public StyledLessonPromptStore(JdbcClient jdbc,JsonMapper json,StyledLessonStore lessons){this.jdbc=jdbc;this.json=json;this.lessons=lessons;}
    @Transactional public Entry start(StyledAssetStore.Work w,JsonNode selected,String base,int budget) {
        require(w,selected);
        var input=json.createObjectNode().put("version",StyledLessonPromptAgent.VERSION).put("basePrompt",base).put("budget",budget);
        input.set("lessons",selected);String key=StyledSpriteCodec.sha(json.writeValueAsBytes(input));
        int inserted=jdbc.sql("""
            INSERT INTO shelter.styled_lesson_prompts(job_id,label,input_sha256,input,lessons_sha256,state,lease_token)
            VALUES (:j,:l,:h,CAST(:input AS jsonb),:lessons,'COMPOSING',:token) ON CONFLICT DO NOTHING
            """).param("j",w.id()).param("l",w.label()).param("h",key).param("input",json.writeValueAsString(input))
            .param("lessons",StyledSpriteCodec.sha(json.writeValueAsBytes(selected))).param("token",w.token()).update();
        return jdbc.sql("SELECT state,draft::text,validation::text FROM shelter.styled_lesson_prompts WHERE job_id=:j AND label=:l AND input_sha256=:h")
            .param("j",w.id()).param("l",w.label()).param("h",key).query((r,n)->new Entry(key,r.getString(1),node(r.getString(2)),node(r.getString(3)),inserted==1)).single();
    }
    @Transactional public void draft(StyledAssetStore.Work w,JsonNode selected,String key,JsonNode draft) {
        require(w,selected);
        if(jdbc.sql("UPDATE shelter.styled_lesson_prompts SET draft=CAST(:draft AS jsonb),history=history || jsonb_build_array(jsonb_build_object('draft',CAST(:draft AS jsonb))),state='VALIDATING',updated_at=now() WHERE job_id=:j AND label=:l AND input_sha256=:h AND state IN ('COMPOSING','VALIDATING') AND lease_token=:t")
            .param("draft",json.writeValueAsString(draft)).param("j",w.id()).param("l",w.label()).param("h",key).param("t",w.token()).update()!=1)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_INTERRUPTED");
    }
    @Transactional public void ready(StyledAssetStore.Work w,JsonNode selected,String key,JsonNode verdict,String description) {
        require(w,selected);
        if(jdbc.sql("UPDATE shelter.styled_lesson_prompts SET validation=CAST(:v AS jsonb),description_sha256=:sha,state='READY',updated_at=now() WHERE job_id=:j AND label=:l AND input_sha256=:h AND state='VALIDATING' AND lease_token=:t")
            .param("v",json.writeValueAsString(verdict)).param("sha",StyledSpriteCodec.sha(description.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .param("j",w.id()).param("l",w.label()).param("h",key).param("t",w.token()).update()!=1)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_INTERRUPTED");
    }
    @Transactional public void verdict(StyledAssetStore.Work w,JsonNode selected,String key,JsonNode verdict) {
        require(w,selected);
        if(jdbc.sql("UPDATE shelter.styled_lesson_prompts SET validation=CAST(:v AS jsonb),history=history || jsonb_build_array(jsonb_build_object('validation',CAST(:v AS jsonb))),updated_at=now() WHERE job_id=:j AND label=:l AND input_sha256=:h AND state='VALIDATING' AND lease_token=:t")
            .param("v",json.writeValueAsString(verdict)).param("j",w.id()).param("l",w.label()).param("h",key).param("t",w.token()).update()!=1)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_INTERRUPTED");
    }
    @Transactional public void failed(StyledAssetStore.Work w,String key,String code) {
        jdbc.sql("UPDATE shelter.styled_lesson_prompts SET state='FAILED',failure_code=:c,updated_at=now() WHERE job_id=:j AND label=:l AND input_sha256=:h AND state<>'READY' AND lease_token=:t")
            .param("c",code).param("j",w.id()).param("l",w.label()).param("h",key).param("t",w.token()).update();
    }
    private void require(StyledAssetStore.Work w,JsonNode selected){if(!lessons.authorized(w,selected))throw StyledLessonPromptAgent.invalid("LESSON_SOURCE_CHANGED");}
    private JsonNode node(String text){return text==null?null:json.readTree(text);}
}
