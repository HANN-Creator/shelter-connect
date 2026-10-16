package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledPromptEditRecoveryTest {
    final JsonMapper json=new JsonMapper();
    final OpenAiResponsesClient client=mock(OpenAiResponsesClient.class);
    JsonNode fixture()throws Exception{return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-length-edit-v7/beagle-north.json")));}
    JsonNode edits(String find,String replacement){return json.valueToTree(Map.of("edits",List.of(Map.of("find",find,"replace",replacement))));}
    AssetException failure(JsonNode source){return new AssetException(409,"LESSON_PROMPT_INVALID",source.path("history").get(0));}
    StyledPromptSpacingRecovery.Recovery recover(JsonNode source){return StyledPromptEditRecovery.recover(client,json,source.at("/input/lessons"),source.at("/input/basePrompt").asText(),1000,failure(source)).orElseThrow();}
    JsonNode shortening() {
        return edits("head silhouette,outline,tail,coat markings and paws fixed;appendages and attached features fixed;tail carriage,position and shape fixed;no repeated pose,appendage,tail or rear silhouette displacement or reshaping;no wag cycle or bob;keep seed tail shape and position fixed;",
            "fixed head silhouette,outline,tail carriage/shape/position,coat markings,paws,appendages and attached features;no pose/feature/rear silhouette displacement or reshaping,wag cycle or bob;");
    }
    @Test void recordedLiveLunaEditsReplayAgainstTheActualFailedDraft()throws Exception {
        var recorded=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-length-edit-v7/live-edit-replay.json")));
        var calls=recorded.at("/diagnostics/lengthEditingAttempts");
        when(client.structured(anyString(),any(),anyMap())).thenReturn(calls.get(0).path("proposal"),calls.get(1).path("proposal"));
        var result=recover(fixture());
        assertThat(result.draft()).isEqualTo(recorded.path("draft"));
        assertThat(result.draft().path("prompt").asText()).hasSize(959);
        assertThat(result.diagnostics().path("lengthEditingAttempts")).isEqualTo(calls);
        verify(client,times(2)).structured(anyString(),any(),anyMap());
    }
    @Test void realSixtyEightCharacterOverflowIsEditedAndAllSourcesReachIndependentAudit()throws Exception {
        var e=fixture();var before=e.toString();var rules=e.at("/input/lessons");String base=e.at("/input/basePrompt").asText();
        assertThat(StyledPromptWordingRecovery.recover(failure(e),1000)).isEmpty();
        when(client.structured(anyString(),any(),anyMap())).thenReturn(shortening());
        var out=recover(e);StyledLessonPromptAgent.checkPrompt(out.draft(),1000);
        assertThat(out.draft().path("prompt").asText()).contains("blink optional","normal blinking","approved pose","<=1px","40x40","every frame","north","eyes","coat");
        assertThat(e.toString()).isEqualTo(before);
        var input=org.mockito.ArgumentCaptor.forClass(Object.class);verify(client).structured(anyString(),input.capture(),anyMap());
        assertThat(json.valueToTree(input.getValue()).path("rules")).isEqualTo(rules);
        assertThat(json.valueToTree(input.getValue()).path("basePrompt").asText()).isEqualTo(base);
        assertThat(out.diagnostics().path("promptDraftFailures")).isEqualTo(e.at("/history/0/promptDraftFailures"));
        assertThat(out.diagnostics().at("/lengthEditingAttempts/0/characters").asInt()).isLessThanOrEqualTo(1000);

        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);
        var work=new StyledVisualWarningsTest().work();
        when(store.start(work,rules,base,1000)).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(agent.compose(rules,base,1000)).thenThrow(failure(e));when(agent.compact(eq(rules),eq(base),eq(1000),any())).thenReturn(Optional.of(out));
        var verdict=json.valueToTree(Map.of("preserved",Collections.nCopies(11,false),"basePreserved",false,"compatibleWithBase",true,"noNewRequirements",true));
        when(agent.verify(rules,base,out.draft(),1000)).thenReturn(verdict);
        assertThat(new StyledLessonPromptComposer(store,agent).describe(work,rules,base,1000)).isEqualTo(out.draft().path("prompt").asText());
        var ordered=inOrder(store,agent);ordered.verify(store).diagnostics(work,rules,"key",out.diagnostics());
        ordered.verify(store).draft(work,rules,"key",out.draft());ordered.verify(agent).verify(rules,base,out.draft(),1000);
        var warning=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(store).ready(eq(work),eq(rules),eq("key"),warning.capture(),anyString());
        assertThat(warning.getValue().path("warning").asText()).isEqualTo("LESSON_PROMPT_MEANING_LOST");
        assertThat(warning.getValue().path("observation")).isEqualTo(verdict);
        verify(agent,never()).rewrite(any(),anyString(),anyInt(),any(),any());
    }
    @Test void editsUseOriginalOffsetsAndRejectAmbiguityOverlapBlankAndExpansion() {
        String original="Keep the entire tail; preserve the entire coat.";
        var both=json.valueToTree(Map.of("edits",List.of(Map.of("find","the entire tail","replace","whole tail"),Map.of("find","the entire coat","replace","whole coat"))));
        assertThat(StyledPromptEditRecovery.apply(original,both)).isEqualTo("Keep whole tail; preserve whole coat.");
        for(var invalid:List.of(edits("the entire","whole"),edits("absent","x"),edits("tail","bigger tail"),edits("tail",""),edits(original,"short."),
            json.valueToTree(Map.of("edits",List.of(Map.of("find","the entire tail","replace","tail"),Map.of("find","entire tail","replace","tail"))))))
            assertThatThrownBy(()->StyledPromptEditRecovery.apply(original,invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictPolicyStillRejectsLostMeaningAfterLengthRecovery()throws Exception {
        var e=fixture();var rules=e.at("/input/lessons");String base=e.at("/input/basePrompt").asText();
        when(client.structured(anyString(),any(),anyMap())).thenReturn(shortening());var recovered=recover(e);
        var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);
        when(store.start(null,rules,base,1000)).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
        when(agent.compose(rules,base,1000)).thenThrow(failure(e));when(agent.compact(eq(rules),eq(base),eq(1000),any())).thenReturn(Optional.of(recovered));
        when(agent.verify(eq(rules),eq(base),any(),eq(1000))).thenReturn(json.valueToTree(Map.of("basePreserved",false)));
        when(agent.rewrite(eq(rules),eq(base),eq(1000),any(),any())).thenReturn(recovered.draft());
        assertThatThrownBy(()->new StyledLessonPromptComposer(store,agent).describe(null,rules,base,1000)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        verify(store,never()).ready(any(),any(),anyString(),any(),anyString());
        verify(store).diagnostics(null,rules,"key",recovered.diagnostics());
        verify(store).failed(null,"key","LESSON_PROMPT_MEANING_LOST");
    }
    @Test void twoInvalidEditsStopWithOriginalAndEditorEvidence()throws Exception {
        var e=fixture();when(client.structured(anyString(),any(),anyMap())).thenReturn(edits("missing phrase","x"));
        assertThatThrownBy(()->recover(e)).isInstanceOf(AssetException.class).satisfies(t->{
            var a=(AssetException)t;assertThat(a.code).isEqualTo("LESSON_PROMPT_INVALID");
            assertThat(a.diagnostics.path("lengthEditingAttempts").size()).isEqualTo(2);
            assertThat(a.diagnostics.at("/lengthEditingAttempts/1/editError").asText()).isEqualTo("EDIT_MATCH_NOT_UNIQUE");
            assertThat(a.diagnostics.path("promptDraftFailures")).isEqualTo(e.at("/history/0/promptDraftFailures"));
        });verify(client,times(2)).structured(anyString(),any(),anyMap());
    }
    @Test void timeoutStopsWithoutRepeatingProviderAndPreservesEvidence()throws Exception {
        var e=fixture();when(client.structured(anyString(),any(),anyMap())).thenThrow(new AiFailure("AI_TIMEOUT"));
        assertThatThrownBy(()->recover(e)).satisfies(t->assertThat(((AssetException)t).diagnostics.at("/lengthEditingAttempts/0/editorFailure").asText()).isEqualTo("AI_TIMEOUT"));
        verify(client).structured(anyString(),any(),anyMap());
    }
    @Test void partialProgressGetsMeasuredFeedbackButUnsupportedCharactersAreNeverAccepted()throws Exception {
        var e=fixture();var inputs=new ArrayList<JsonNode>();
        when(client.structured(anyString(),any(),anyMap())).thenAnswer(c->{inputs.add(json.valueToTree(c.getArgument(1,Object.class)));return inputs.size()==1?edits("including the final hold","incl. final hold"):shortening();});
        var out=recover(e);assertThat(inputs).hasSize(2);
        assertThat(inputs.get(1).path("measuredCharacters").asInt()).isLessThan(inputs.get(0).path("measuredCharacters").asInt());
        assertThat(out.draft().path("prompt").asText()).contains("incl. final hold");
        reset(client);when(client.structured(anyString(),any(),anyMap())).thenReturn(edits("including the final hold","final—hold"));
        assertThatThrownBy(()->recover(e)).isInstanceOf(AssetException.class);
    }
    @Test void nonLengthFailuresDoNotCallEditor()throws Exception {
        var e=fixture();
        for(var f:List.of(new AssetException(409,"LESSON_PROMPT_INTERRUPTED",e.at("/history/0")),new AssetException(409,"LESSON_PROMPT_INVALID"),
            new AssetException(409,"LESSON_PROMPT_INVALID",json.valueToTree(Map.of("promptDraftFailures",List.of(Map.of("draft",Map.of("prompt","Overlong but unfinished"))))))))
            assertThat(StyledPromptEditRecovery.recover(client,json,e.at("/input/lessons"),"Base",15,f)).isEmpty();
        verifyNoInteractions(client);
    }
}
