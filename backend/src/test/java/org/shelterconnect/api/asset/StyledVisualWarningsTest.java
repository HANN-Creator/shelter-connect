package org.shelterconnect.api.asset;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledVisualWarningsTest {
    final JsonMapper json=new JsonMapper();
    StyledAssetStore.Work work() {
        return new StyledAssetStore.Work(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"walk-west","WALK","west","PENDING",null,null,
            "dog-photos","source.png",json.createObjectNode(),null,null,null,0,json.createObjectNode().put("visualWarnings",StyledVisualWarnings.VERSION));
    }
    @Test void observationFailureRemainsFalseAndExplicitlyUnavailable() {
        var r=StyledVisualWarnings.report(json,json.createObjectNode(),"AI_TIMEOUT");
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("reviewStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(r.at("/qualityWarnings/0/reason").asText()).isEqualTo("AI_TIMEOUT");
    }
    @Test void semanticPromptConcernsRemainWarningsAndAllOriginalLessonsReachTheWriter() {
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);var w=work();
        var lessons=json.createArrayNode();for(int i=0;i<7;i++)lessons.addObject().put("id","rule-"+i).put("prevention","Preserve specific feature "+i+".");
        var draft=json.createObjectNode().put("prompt","Every frame; native pixels; preserve approved eyes, coat and tail; walk west.");
        var observation=json.valueToTree(Map.of("preserved",Collections.nCopies(7,false),"basePreserved",false,"compatibleWithBase",true,"noNewRequirements",true));
        when(store.start(w,lessons,"Walk west.",1000)).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(agent.compose(lessons,"Walk west.",1000)).thenReturn(draft);when(agent.verify(lessons,"Walk west.",draft,1000)).thenReturn(observation);
        assertThat(new StyledLessonPromptComposer(store,agent).describe(w,lessons,"Walk west.",1000)).isEqualTo(draft.path("prompt").asText());
        verify(agent).compose(lessons,"Walk west.",1000);verify(agent,never()).rewrite(any(),anyString(),anyInt(),any(),any());
        var audit=ArgumentCaptor.forClass(JsonNode.class);verify(store).ready(eq(w),eq(lessons),eq("key"),audit.capture(),eq(draft.path("prompt").asText()));
        assertThat(audit.getValue().path("observation")).isEqualTo(observation);assertThat(audit.getValue().path("warning").asText()).isEqualTo("LESSON_PROMPT_MEANING_LOST");
    }
    @Test void advisoryModeStillRejectsPromptBeyondProviderLimit() {
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);var w=work();var lessons=json.createArrayNode();
        when(store.start(w,lessons,"Walk west.",1000)).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(agent.compose(lessons,"Walk west.",1000)).thenReturn(json.createObjectNode().put("prompt","x".repeat(1000)+"."));
        assertThatThrownBy(()->new StyledLessonPromptComposer(store,agent).describe(w,lessons,"Walk west.",1000)).isInstanceOf(AssetException.class);
        verify(store,never()).ready(any(),any(),anyString(),any(),anyString());verify(agent,never()).verify(any(),anyString(),any(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"code","frame","direction","base-frame","note"})
    void malformedWarningNeverBecomesAGenuineClearObservation(String defect) {
        var client=mock(OpenAiResponsesClient.class);var props=new AiProperties(true,"fake-key","test-model",30);
        var w=json.createObjectNode().put("code","TAIL").put("direction","west").put("note","확인이 필요합니다.");w.putArray("frames").add(2);
        String action="WALK";
        switch(defect){case "code"->w.put("code","ALLOW");case "frame"->w.putArray("frames").add(9);case "direction"->w.put("direction","east");case "base-frame"->action="BASE";case "note"->w.put("note","x".repeat(501));}
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.createObjectNode().set("warnings",json.createArrayNode().add(w)));
        var im=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);im.setRGB(8,8,0xffaaaaaa);byte[] image=StyledSpriteCodec.png(im);
        String a=action;assertThatThrownBy(()->new StyledVisualWarningAgent(client,props,json).observe(Collections.nCopies(4,image),a.equals("BASE")?List.of():Collections.nCopies(9,StyledSpriteCodec.paddedSeed(image)),a,"west"))
            .isInstanceOf(AssetException.class);verify(client,times(1)).structuredImage(anyString(),anyString(),any(),anyMap());
    }
}
