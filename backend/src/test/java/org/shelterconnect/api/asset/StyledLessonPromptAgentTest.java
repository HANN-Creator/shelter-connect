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
    JsonNode verdict(int count){return json.valueToTree(Map.of("preserved",Collections.nCopies(count,true),"compatibleWithBase",true,"noNewRequirements",true,"basePreserved",true));}
    @Test void actualRepairWithOnlyEightyThreeAdditiveCharactersUsesWholeProviderBudget() throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/fixtures/full-prompt-budget-v2/evidence.json")));
        var input=fixture.at("/failed/input");var sources=input.path("lessons");String base=input.path("basePrompt").asText();
        assertThat(1000-base.length()-10).isEqualTo(83);
        var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);
        when(store.start(isNull(),eq(sources),eq(base),eq(1000))).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        var draft=json.valueToTree(Map.of("prompt","Preserve every base requirement and every approved subtle idle requirement."));
        when(writer.compose(eq(sources),eq(base),eq(1000))).thenReturn(draft);
        var accepted=(tools.jackson.databind.node.ObjectNode)verdict(4);accepted.put("basePreserved",true);
        when(writer.verify(eq(sources),eq(base),eq(draft),eq(1000))).thenReturn(accepted);
        assertThat(new StyledLessonPromptComposer(store,writer).describe(null,sources,base,1000)).isEqualTo(draft.path("prompt").asText());
        verify(store).ready(isNull(),eq(sources),eq("key"),eq(accepted),eq(draft.path("prompt").asText()));
    }
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
        assertThatThrownBy(()->agent.compose(rules(8),"Base",15)).hasMessage("LESSON_PROMPT_INVALID").satisfies(error->{
            var diagnostics=((AssetException)error).diagnostics.path("promptDraftFailures");assertThat(diagnostics.size()).isEqualTo(3);
            assertThat(diagnostics.get(2).path("draft")).isEqualTo(draft);
            assertThat(diagnostics.get(2).path("measuredCharacters").asInt()).isEqualTo(draft.path("prompt").asText().length());
        });
        verify(client,times(3)).structured(anyString(),any(),anyMap());
    }
    @Test void missingBaseCoverageAndLostNumericOrDirectionalConditionsBlockPurchase() {
        var missing=(tools.jackson.databind.node.ObjectNode)verdict(4);missing.remove("basePreserved");
        assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(missing,4)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        var draft=json.valueToTree(Map.of("prompt","Every frame: south; 40x40 transparent, edges >=2px clear; chest breath <=1px; no wag."));
        StyledLessonPromptAgent.checkPrompt(draft,1000);
        var rejected=(tools.jackson.databind.node.ObjectNode)verdict(4);rejected.put("basePreserved",false);
        var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);var sources=rules(4);
        when(store.start(isNull(),eq(sources),anyString(),eq(1000))).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(writer.compose(any(),anyString(),anyInt())).thenReturn(draft);when(writer.rewrite(any(),anyString(),anyInt(),any(),any())).thenReturn(draft);
        when(writer.verify(any(),anyString(),any(),anyInt())).thenReturn(rejected);
        assertThatThrownBy(()->new StyledLessonPromptComposer(store,writer).describe(null,sources,"Preserve numeric and direction conditions.",1000)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        verify(store,never()).ready(any(),any(),anyString(),any(),anyString());verify(store).failed(isNull(),eq("key"),eq("LESSON_PROMPT_MEANING_LOST"));
    }
    @Test void optimisticModelVerdictCannotOmitEyesCoatDirectionOrCanvas() {
        var draft=json.valueToTree(Map.of("prompt","Keep the head silhouette unchanged."));
        when(client.structured(anyString(),any(),anyMap())).thenReturn(verdict(4));
        var checked=agent.verify(rules(4),"Same coat, eyes; south facing on 40x40 canvas.",draft,1000);
        assertThat(checked.path("modelBasePreserved").asBoolean()).isTrue();assertThat(checked.path("basePreserved").asBoolean()).isFalse();
        assertThat(checked.path("missingLiteralAnchors").valueStream().map(JsonNode::asText).toList()).containsExactly("eyes","coat","south","40x40");
        assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(checked,4)).hasMessage("LESSON_PROMPT_MEANING_LOST");
    }
    @Test void terminalFormatDiagnosticsAreSavedBeforeFailureWithoutLeakingIntoImageQuality() {
        var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);var sources=rules(4);
        var diagnostics=json.valueToTree(Map.of("promptDraftFailures",List.of(Map.of("draft","overlong","measuredCharacters",1100))));
        when(store.start(isNull(),eq(sources),anyString(),anyInt())).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(writer.compose(any(),anyString(),anyInt())).thenThrow(new AssetException(409,"LESSON_PROMPT_INVALID",diagnostics));
        assertThatThrownBy(()->new StyledLessonPromptComposer(store,writer).describe(null,sources,"Base",1000)).satisfies(e->assertThat(((AssetException)e).diagnostics).isNull());
        var order=inOrder(store);order.verify(store).start(isNull(),eq(sources),eq("Base"),eq(1000));
        order.verify(store).diagnostics(isNull(),eq(sources),eq("key"),eq(diagnostics));order.verify(store).failed(isNull(),eq("key"),eq("LESSON_PROMPT_INVALID"));
    }
    @Test void missingWeakenedExtraOrNonBooleanCoverageCannotPass() {
        for(JsonNode result:List.of(verdict(2),verdict(4),json.readTree("{\"preserved\":[true,false,true],\"compatibleWithBase\":true,\"noNewRequirements\":true}"),
            json.readTree("{\"preserved\":[true,\"true\",true],\"compatibleWithBase\":true,\"noNewRequirements\":true}")))
            assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(result,3)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        for(String field:List.of("basePreserved","compatibleWithBase","noNewRequirements")) {
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
    @Test void actualUnderBudgetDiagnosticLabelsPassFormatWithoutChangingTheDraft() throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/fixtures/prompt-format-v3/evidence.json")));
        var input=fixture.path("input");var drafts=fixture.at("/history/0/promptDraftFailures");
        assertThat(drafts.valueStream().map(d->d.path("measuredCharacters").asInt()).toList()).containsExactly(1251,1226,1289);
        for(var attempt:drafts) {
            var draft=attempt.path("draft");String text=draft.path("prompt").asText();
            assertThat(text).contains("ACTION_MISSING","IDLE_MOTION");
            assertThat(text.replace("_","").matches("[A-Za-z0-9 ,.;:'()!?<>=/+-]+")).isTrue();
            assertThat(StyledLessonPromptAgent.formatErrors(draft,2000)).isEmpty();
            when(client.structured(anyString(),any(),anyMap())).thenReturn(draft);
            assertThat(agent.compose(input.path("lessons"),input.path("basePrompt").asText(),2000)).isSameAs(draft);
        }
        verify(client,times(3)).structured(anyString(),any(),anyMap());
        // Format acceptance does not waive semantic validation or claim these are good images.
        var missing=(tools.jackson.databind.node.ObjectNode)verdict(2);missing.put("basePreserved",false);
        assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(missing,2)).hasMessage("LESSON_PROMPT_MEANING_LOST");
    }
    @Test void unsupportedPunctuationGetsExactFeedbackAndDoesNotPretendToBeOverBudget() {
        var bad=json.valueToTree(Map.of("prompt","Keep the tail still—no wag."));
        var good=json.valueToTree(Map.of("prompt","Keep the tail still; no wag."));
        var inputs=new ArrayList<JsonNode>();
        when(client.structured(anyString(),any(),anyMap())).thenAnswer(c->{inputs.add(json.valueToTree(c.getArgument(1,Object.class)));return inputs.size()==1?bad:good;});
        assertThat(agent.compose(rules(2),"Keep tail still.",2000)).isEqualTo(good);
        var feedback=inputs.get(1);
        assertThat(feedback.path("formatErrors").valueStream().map(JsonNode::asText).toList()).containsExactly("UNSUPPORTED_CHARACTERS: U+2014");
        assertThat(feedback.path("charactersOverBudget").asInt()).isZero();assertThat(feedback.path("rejectedDraft")).isEqualTo(bad);
        assertThat(feedback.path("revisionRequired").asText()).contains("shortening is not required","never truncate");
        assertThat(feedback.path("rules")).isEqualTo(inputs.get(0).path("rules"));
    }
    @Test void persistentFormatFailureRetainsEveryOriginalDraftAndPreciseReason() {
        var bad=json.valueToTree(Map.of("prompt","Keep pixels still—without wag."));
        when(client.structured(anyString(),any(),anyMap())).thenReturn(bad);
        assertThatThrownBy(()->agent.compose(rules(2),"Keep still.",2000)).satisfies(e->{
            var attempts=((AssetException)e).diagnostics.path("promptDraftFailures");assertThat(attempts.size()).isEqualTo(3);
            for(var attempt:attempts) {
                assertThat(attempt.path("draft")).isEqualTo(bad);
                assertThat(attempt.path("formatErrors").toString()).contains("U+2014").doesNotContain("TOO_LONG");
            }
        });
    }
    @Test void labelSupportDoesNotAcceptStructuredOrUnboundedInstructions() {
        for(String s:List.of("Keep a tail. https://example.com", "Keep tail still\nno wag.", "Keep ${tail} still.", "Keep `tail` still.", "Keep tail [0] still.", " Keep tail still.", "Keep tail still; no wag"))
            assertThatThrownBy(()->StyledLessonPromptAgent.checkPrompt(json.valueToTree(Map.of("prompt",s)),2000)).hasMessage("LESSON_PROMPT_INVALID");
        assertThat(StyledLessonPromptAgent.formatErrors(json.valueToTree(Map.of("prompt",17)),2000)).anyMatch(e->e.startsWith("SHAPE"));
        assertThat(StyledLessonPromptAgent.formatErrors(json.valueToTree(Map.of("prompt","Keep all tail pixels still.","extra",true)),2000)).anyMatch(e->e.startsWith("SHAPE"));
    }
    @Test void noSpaceAndDisabledAiNeverCallTheModel() {
        assertThatThrownBy(()->agent.compose(rules(4),"Base",14)).hasMessage("LESSON_PROMPT_NO_SPACE");
        var disabled=new StyledLessonPromptAgent(client,json,new AiProperties(false,"","gpt-5.6-luna",30));
        assertThatThrownBy(()->disabled.compose(rules(4),"Base",100)).hasMessage("AI_UNAVAILABLE");verifyNoInteractions(client);
    }
    @Test void cachedReadyCompletePromptDoesNotCallTheAgent() {
        var store=mock(StyledLessonPromptStore.class);var writer=mock(StyledLessonPromptAgent.class);
        var composer=new StyledLessonPromptComposer(store,writer);var rules=rules(4);
        var draft=json.valueToTree(Map.of("prompt","Keep every tail pixel inside the canvas."));
        when(store.start(isNull(),eq(rules),eq("Base unchanged."),anyInt())).thenReturn(new StyledLessonPromptStore.Entry("key","READY",draft,verdict(4),false));
        assertThat(composer.describe(null,rules,"Base unchanged.",100)).isEqualTo("Keep every tail pixel inside the canvas.");
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
