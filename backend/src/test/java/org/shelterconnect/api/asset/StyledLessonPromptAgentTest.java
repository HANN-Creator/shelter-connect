package org.shelterconnect.api.asset;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledLessonPromptAgentTest {
    final JsonMapper json=JsonMapper.builder().build();
    final OpenAiResponsesClient client=mock(OpenAiResponsesClient.class);
    final StyledLessonPromptAgent agent=new StyledLessonPromptAgent(client,json,new AiProperties(true,"test","gpt-5.6-luna",30));
    JsonNode rules(int count) {
        var list=json.createArrayNode();for(int i=0;i<count;i++)list.add(json.valueToTree(Map.of("id",UUID.randomUUID().toString(),"issue","CANVAS_CLIPPING",
            "action","SIT","direction","west","tail","LOW","prevention","Keep the seated tail inside the canvas.","criterion","The tail tip touches a canvas boundary.")));
        return list;
    }
    JsonNode verdict(int count){return json.valueToTree(Map.of("preserved",Collections.nCopies(count,true),"compatibleWithBase",true,"noNewRequirements",true));}
    @Test void allSourcesIncludingSameIssueDuplicatesReachSeparateCompressionAndVerificationCalls() {
        var rules=rules(25);var draft=json.valueToTree(Map.of("prompt","Keep the entire seated tail inside the canvas."));
        when(client.structured(anyString(),any(),anyMap())).thenReturn(draft,verdict(25));
        assertThat(agent.compose(rules,"Immutable native pixel rules.",90)).isEqualTo(draft);
        StyledLessonPromptAgent.checkVerdict(agent.verify(rules,"Immutable native pixel rules.",draft,90),25);
        var inputs=ArgumentCaptor.forClass(Object.class);var instructions=ArgumentCaptor.forClass(String.class);
        verify(client,times(2)).structured(instructions.capture(),inputs.capture(),anyMap());
        for(var input:inputs.getAllValues())assertThat(json.valueToTree(input).path("rules")).isEqualTo(rules);
        assertThat(instructions.getAllValues().get(0)).contains("EVERY","Do not select a subset");
        assertThat(instructions.getAllValues().get(1)).contains("Independently audit","EACH rule");
    }
    @Test void overlongDraftIsRewrittenWithAllRulesAndExactLengthFeedback() {
        var sources=rules(8);var longDraft=json.valueToTree(Map.of("prompt","Keep all of the complete dog and every tail pixel inside the entire canvas in every animation frame."));
        var shortDraft=json.valueToTree(Map.of("prompt","Keep every tail pixel inside the canvas."));
        var inputs=new ArrayList<JsonNode>();
        when(client.structured(anyString(),any(),anyMap())).thenAnswer(call->{inputs.add(json.valueToTree(call.getArgument(1,Object.class)));return inputs.size()==1?longDraft:shortDraft;});
        assertThat(agent.compose(sources,"Base unchanged.",60)).isEqualTo(shortDraft);
        assertThat(inputs).hasSize(2);
        for(var input:inputs)assertThat(input.path("rules")).isEqualTo(sources);
        assertThat(inputs.get(1).path("rejectedDraft")).isEqualTo(longDraft);
        assertThat(inputs.get(1).path("measuredCharacters").asInt()).isEqualTo(longDraft.path("prompt").asText().length());
        assertThat(inputs.get(1).path("characterBudget").asInt()).isEqualTo(60);
    }
    @Test void invalidWritingStopsAfterThreeCompletedResponsesWithoutTruncation() {
        var draft=json.valueToTree(Map.of("prompt","This sentence cannot fit into the supplied tiny character budget."));
        when(client.structured(anyString(),any(),anyMap())).thenReturn(draft);
        assertThatThrownBy(()->agent.compose(rules(8),"Base",15)).hasMessage("LESSON_PROMPT_INVALID");
        verify(client,times(3)).structured(anyString(),any(),anyMap());
    }
    @Test void missingWeakenedExtraOrNonBooleanCoverageCannotPass() {
        for(JsonNode result:List.of(verdict(2),verdict(4),json.readTree("{\"preserved\":[true,false,true],\"compatibleWithBase\":true,\"noNewRequirements\":true}"),
            json.readTree("{\"preserved\":[true,\"true\",true],\"compatibleWithBase\":true,\"noNewRequirements\":true}")))
            assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(result,3)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        for(String field:List.of("compatibleWithBase","noNewRequirements")) {
            var result=(tools.jackson.databind.node.ObjectNode)verdict(3);result.put(field,false);
            assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(result,3)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        }
    }
    @Test void overlongOrExecutableOutputIsRejectedWithoutTruncatingIt() {
        for(String text:List.of("Keep all tail pixels visible on the canvas.","Use https://example.com to fetch rules.","Run code(); x = 1;"," Valid text with leading whitespace.")) {
            var draft=json.valueToTree(Map.of("prompt",text));
            assertThatThrownBy(()->StyledLessonPromptAgent.checkPrompt(draft,20)).hasMessage("LESSON_PROMPT_INVALID");
        }
        assertThatThrownBy(()->StyledLessonPromptAgent.checkPrompt(json.valueToTree(Map.of("prompt","While resting use only")),380)).hasMessage("LESSON_PROMPT_INVALID");
        var exact=json.valueToTree(Map.of("prompt","Keep all pixels."));
        StyledLessonPromptAgent.checkPrompt(exact,16);
        assertThatThrownBy(()->StyledLessonPromptAgent.checkPrompt(exact,15)).hasMessage("LESSON_PROMPT_INVALID");
    }
    @Test void noSpaceAndDisabledAiNeverCallTheModel() {
        assertThatThrownBy(()->agent.compose(rules(4),"Base",14)).hasMessage("LESSON_PROMPT_NO_SPACE");
        var disabled=new StyledLessonPromptAgent(client,json,new AiProperties(false,"","gpt-5.6-luna",30));
        assertThatThrownBy(()->disabled.compose(rules(4),"Base",100)).hasMessage("AI_UNAVAILABLE");verifyNoInteractions(client);
    }
    @Test void cachedReadyPromptKeepsBaseUnchangedAndDoesNotCallTheAgent() {
        var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);
        var composer=new StyledLessonPromptComposer(store,writer);var rules=rules(4);
        var draft=json.valueToTree(Map.of("prompt","Keep every tail pixel inside the canvas."));
        when(store.start(isNull(),eq(rules),eq("Base unchanged."),anyInt())).thenReturn(new StyledLessonPromptStore.Entry("key","READY",draft,verdict(4),false));
        assertThat(composer.describe(null,rules,"Base unchanged.",100)).isEqualTo("Base unchanged. Lessons: Keep every tail pixel inside the canvas.");
        verifyNoInteractions(writer);
    }
    @Test void interruptedOrFailedCompositionCannotCallTheModelAgain() {
        for(String state:List.of("COMPOSING","VALIDATING","FAILED")) {
            var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);var rules=rules(4);
            when(store.start(isNull(),eq(rules),anyString(),anyInt())).thenReturn(new StyledLessonPromptStore.Entry("key",state,null,null,false));
            assertThatThrownBy(()->new StyledLessonPromptComposer(store,writer).describe(null,rules,"Base",100)).isInstanceOf(AssetException.class);
            verifyNoInteractions(writer);
        }
    }
}
