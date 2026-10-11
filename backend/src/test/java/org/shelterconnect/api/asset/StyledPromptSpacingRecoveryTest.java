package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledPromptSpacingRecoveryTest {
    final JsonMapper json=JsonMapper.builder().build();
    JsonNode fixture()throws Exception{return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-spacing-v4/evidence.json")));}
    AssetException failure(JsonNode diagnostics){return new AssetException(409,"LESSON_PROMPT_INVALID",diagnostics);}
    JsonNode diagnostics(JsonNode draft){return json.valueToTree(Map.of("promptDraftFailures",List.of(Map.of("draft",draft))));}
    @Test void actualCompletedResponsesRecoverNewestFittingDraftWithoutChangingAnyContent()throws Exception {
        var evidence=fixture();var diagnostics=evidence.at("/history/0");String unchanged=diagnostics.toString();
        var result=StyledPromptSpacingRecovery.recover(failure(diagnostics),1000).orElseThrow();
        var audit=result.diagnostics().path("spacingRecovery");var source=diagnostics.at("/promptDraftFailures/1/draft/prompt").asText();
        String prompt=result.draft().path("prompt").asText();
        assertThat(audit.path("selectedAttempt").asInt()).isEqualTo(2);
        assertThat(audit.path("originalCharacters").asInt()).isEqualTo(1013);assertThat(prompt).hasSize(974);
        assertThat(prompt.replace(" ","")).isEqualTo(source.replace(" ",""));
        assertThat(prompt.split("[^A-Za-z0-9_]+")).containsExactly(source.split("[^A-Za-z0-9_]+"));
        assertThat(prompt).contains("<=1px","40x40","eyes","coat","north");
        assertThat(result.diagnostics().path("promptDraftFailures")).isEqualTo(diagnostics.path("promptDraftFailures"));
        assertThat(diagnostics.toString()).isEqualTo(unchanged);StyledLessonPromptAgent.checkPrompt(result.draft(),1000);
        var lastOnly=json.createObjectNode().set("promptDraftFailures",json.createArrayNode().add(diagnostics.at("/promptDraftFailures/2")));
        assertThat(StyledPromptSpacingRecovery.recover(failure(lastOnly),1000)).isEmpty(); // Still 1002; never truncate two characters.
    }
    @Test void otherFormatErrorsAndUnknownOutcomesCannotBeRepairedByWhitespace() {
        for(String prompt:List.of("Keep all pixels—still; no wag."," Keep all pixels; no wag.","Keep all pixels; no wag", "Keep https://example.com; no wag."))
            assertThat(StyledPromptSpacingRecovery.recover(failure(diagnostics(json.valueToTree(Map.of("prompt",prompt)))),20)).isEmpty();
        var valid=json.valueToTree(Map.of("prompt","Keep all pixels; no wag."));
        assertThat(StyledPromptSpacingRecovery.recover(failure(diagnostics(valid)),1000)).isEmpty();
        assertThat(StyledPromptSpacingRecovery.recover(new AssetException(409,"LESSON_PROMPT_INTERRUPTED",diagnostics(valid)),20)).isEmpty();
        assertThat(StyledPromptSpacingRecovery.recover(failure(null),20)).isEmpty();
        assertThat(StyledPromptSpacingRecovery.recover(failure(diagnostics(json.valueToTree(Map.of("prompt","No wag; no bob.","extra",true)))),15)).isEmpty();
        var numeric=json.valueToTree(Map.of("prompt","Bounds 1, 000; 2. 3; a > 2; no wag."));
        assertThat(StyledPromptSpacingRecovery.recover(failure(diagnostics(numeric)),33).orElseThrow().draft().path("prompt").asText())
            .contains("1, 000","2. 3","a > 2");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void initialAndSemanticRewriteRecoveryStillRequireIndependentFullReview(boolean onRewrite)throws Exception {
        var evidence=fixture();var sources=evidence.at("/input/lessons");String base=evidence.at("/input/basePrompt").asText();
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);
        var accepted=json.valueToTree(Map.of("preserved",Collections.nCopies(sources.size(),true),"basePreserved",true,"compatibleWithBase",true,"noNewRequirements",true));
        var rejected=json.valueToTree(Map.of("preserved",Collections.nCopies(sources.size(),false),"basePreserved",false,"compatibleWithBase",true,"noNewRequirements",true));
        when(store.start(isNull(),eq(sources),eq(base),eq(1000))).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        var failure=failure(evidence.at("/history/0"));
        if(onRewrite) {
            when(agent.compose(any(),anyString(),anyInt())).thenReturn(json.valueToTree(Map.of("prompt","Incomplete requirements here.")));
            when(agent.verify(any(),anyString(),any(),anyInt())).thenReturn(rejected,accepted);
            when(agent.rewrite(any(),anyString(),anyInt(),any(),any())).thenThrow(failure);
        } else {
            when(agent.compose(any(),anyString(),anyInt())).thenThrow(failure);
            when(agent.verify(any(),anyString(),any(),anyInt())).thenReturn(accepted);
        }
        var recovered=StyledPromptSpacingRecovery.recover(failure,1000).orElseThrow();
        assertThat(new StyledLessonPromptComposer(store,agent).describe(null,sources,base,1000)).isEqualTo(recovered.draft().path("prompt").asText());
        var order=inOrder(store,agent);order.verify(store).diagnostics(isNull(),eq(sources),eq("key"),eq(recovered.diagnostics()));
        order.verify(store).draft(isNull(),eq(sources),eq("key"),eq(recovered.draft()));
        order.verify(agent).verify(eq(sources),eq(base),eq(recovered.draft()),eq(1000));
        order.verify(store).verdict(isNull(),eq(sources),eq("key"),eq(accepted));
        order.verify(store).ready(isNull(),eq(sources),eq("key"),eq(accepted),anyString());
    }
    @Test void actualSemanticRejectionsRemainBlockingAndGuideTargetedRevision()throws Exception {
        var saved=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-spacing-v4/semantic-feedback.json"))).path("receipts");
        for(int index:List.of(0,3,5))assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(saved.get(index),4)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        var client=mock(org.shelterconnect.api.chat.OpenAiResponsesClient.class);
        when(client.structured(anyString(),any(),anyMap())).thenReturn(saved.get(4));
        var agent=new StyledLessonPromptAgent(client,json,new org.shelterconnect.api.chat.AiProperties(true,"test","gpt-5.6-luna",30));
        var evidence=fixture();agent.rewrite(evidence.at("/input/lessons"),evidence.at("/input/basePrompt").asText(),1000,saved.get(4),saved.get(5));
        var input=org.mockito.ArgumentCaptor.forClass(Object.class);verify(client).structured(anyString(),input.capture(),anyMap());
        var sent=json.valueToTree(input.getValue());assertThat(sent.path("semanticFeedback")).isEqualTo(saved.get(5));
        assertThat(sent.path("rejectedDraft")).isEqualTo(saved.get(4));
        assertThat(sent.path("revisionRequired").asText()).contains("targeted edits","already verified requirements","normal","hard budget");
        assertThat(sent.path("rules").size()).isEqualTo(4);
    }
}
