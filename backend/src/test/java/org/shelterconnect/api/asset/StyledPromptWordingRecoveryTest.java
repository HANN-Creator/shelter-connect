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

class StyledPromptWordingRecoveryTest {
    final JsonMapper json=new JsonMapper();
    JsonNode fixture()throws Exception{return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/batch-input-v6/prompt-evidence.json")));}
    AssetException failure(JsonNode diagnostic){return new AssetException(409,"LESSON_PROMPT_INVALID",diagnostic);}
    @Test void actualSixteenCharacterOverflowKeepsAllClausesAndEvidence()throws Exception {
        var evidence=fixture();var diagnostic=evidence.at("/history/0");String original=diagnostic.toString();
        assertThat(StyledPromptSpacingRecovery.recover(failure(diagnostic),1000)).isEmpty();
        var recovered=StyledPromptWordingRecovery.recover(failure(diagnostic),1000).orElseThrow();
        String prompt=recovered.draft().path("prompt").asText();
        assertThat(prompt).hasSize(998).contains("40x40","all edges >=2px clear","approved identity","coat and eyes unchanged",
            "hidden tail stays hidden behind body","never add a tail above head or ears","no visible eyes,nose,mouth or chest",
            "Every frame remains north facing,incl. final hold","normal blinking","chest breath <=1px","blink optional",
            "no repeated appendage or feature displacement or reshaping","no tail carriage shift","no wag cycle or bob",
            "no cropping,shrinking,blur or detached parts","loop smoothly to start pose.");
        assertThat(prompt.split(";")).hasSameSizeAs(diagnostic.at("/promptDraftFailures/2/draft/prompt").asText().split(";"));
        assertThat(recovered.diagnostics().at("/wordingRecovery/selectedAttempt").asInt()).isEqualTo(3);
        assertThat(recovered.diagnostics().at("/wordingRecovery/originalCharacters").asInt()).isEqualTo(1016);
        assertThat(recovered.diagnostics().path("promptDraftFailures")).isEqualTo(diagnostic.path("promptDraftFailures"));
        assertThat(diagnostic.toString()).isEqualTo(original);
        assertThat(evidence.at("/input/lessons").size()).isEqualTo(11);
        StyledLessonPromptAgent.checkPrompt(recovered.draft(),1000);
        assertThat(StyledPromptWordingRecovery.recover(failure(diagnostic),900)).isEmpty(); // Never cut a clause to fit.
    }
    @Test void formatViolationsAndUnknownPaidOutcomesRemainFailures()throws Exception {
        var diagnostic=fixture().at("/history/0");
        assertThat(StyledPromptWordingRecovery.recover(new AssetException(409,"LESSON_PROMPT_INTERRUPTED",diagnostic),1000)).isEmpty();
        assertThat(StyledPromptWordingRecovery.recover(failure(null),1000)).isEmpty();
        for(String text:List.of(" Keep native dog pixel size; including final hold.","Keep native dog pixel size—no wag.",
                "Keep native dog pixel size; no wag", "Keep https://example.com; native dog pixel size.")) {
            var d=json.valueToTree(Map.of("promptDraftFailures",List.of(Map.of("draft",Map.of("prompt",text)))));
            assertThat(StyledPromptWordingRecovery.recover(failure(d),25)).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void initialAndRewritePathsStillAuditTheCompleteElevenRuleInput(boolean rewrite)throws Exception {
        var e=fixture();var lessons=e.at("/input/lessons");String base=e.at("/input/basePrompt").asText();
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);
        when(store.start(isNull(),eq(lessons),eq(base),eq(1000))).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        var accepted=json.valueToTree(Map.of("preserved",Collections.nCopies(11,true),"basePreserved",true,"compatibleWithBase",true,"noNewRequirements",true));
        var rejected=json.valueToTree(Map.of("preserved",Collections.nCopies(11,false),"basePreserved",false,"compatibleWithBase",true,"noNewRequirements",true));
        if(rewrite) {
            when(agent.compose(any(),anyString(),anyInt())).thenReturn(json.valueToTree(Map.of("prompt","Incomplete requirements here.")));
            when(agent.verify(any(),anyString(),any(),anyInt())).thenReturn(rejected,accepted);
            when(agent.rewrite(any(),anyString(),anyInt(),any(),any())).thenThrow(failure(e.at("/history/0")));
        } else {
            when(agent.compose(any(),anyString(),anyInt())).thenThrow(failure(e.at("/history/0")));
            when(agent.verify(any(),anyString(),any(),anyInt())).thenReturn(accepted);
        }
        var recovered=StyledPromptWordingRecovery.recover(failure(e.at("/history/0")),1000).orElseThrow();
        assertThat(new StyledLessonPromptComposer(store,agent).describe(null,lessons,base,1000)).isEqualTo(recovered.draft().path("prompt").asText());
        var order=inOrder(store,agent);
        order.verify(store).diagnostics(isNull(),eq(lessons),eq("key"),eq(recovered.diagnostics()));
        order.verify(store).draft(isNull(),eq(lessons),eq("key"),eq(recovered.draft()));
        order.verify(agent).verify(eq(lessons),eq(base),eq(recovered.draft()),eq(1000));
        order.verify(store).verdict(isNull(),eq(lessons),eq("key"),eq(accepted));
        order.verify(store).ready(isNull(),eq(lessons),eq("key"),eq(accepted),anyString());
    }
    @Test void recoveredTextDoesNotConvertSemanticWarningsToPassOrTriggerVisualRepairs()throws Exception {
        var e=fixture();var lessons=e.at("/input/lessons");String base=e.at("/input/basePrompt").asText();
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);var work=new StyledVisualWarningsTest().work();
        var rejected=json.valueToTree(Map.of("preserved",Collections.nCopies(11,false),"basePreserved",false,"compatibleWithBase",true,"noNewRequirements",true));
        when(store.start(work,lessons,base,1000)).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(agent.compose(lessons,base,1000)).thenThrow(failure(e.at("/history/0")));
        when(agent.verify(eq(lessons),eq(base),any(),eq(1000))).thenReturn(rejected);
        assertThat(new StyledLessonPromptComposer(store,agent).describe(work,lessons,base,1000)).hasSize(998);
        var audit=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(store).ready(eq(work),eq(lessons),eq("key"),audit.capture(),anyString());
        assertThat(audit.getValue().path("observation")).isEqualTo(rejected);
        assertThat(audit.getValue().path("warning").asText()).isEqualTo("LESSON_PROMPT_MEANING_LOST");
        verify(agent,never()).rewrite(any(),anyString(),anyInt(),any(),any());
    }
}
