package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledPromptScopeTest {
    final JsonMapper json=JsonMapper.builder().build();
    JsonNode fixture()throws Exception{return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-scope-v5/evidence.json")));}
    JsonNode accepted(){return json.valueToTree(Map.of("basePreserved",true,"preserved",Collections.nCopies(5,true),
        "compatibleWithBase",true,"noNewRequirements",true,"missingBaseConditions",List.of(),"addedRequirements",List.of(),"conflictingRequirements",List.of()));}

    @Test void savedFailureIsBlockedAndAllSourcesReachInitialAndRewritePaths()throws Exception {
        var evidence=fixture();var input=evidence.path("input");var lessons=input.path("lessons");String base=input.path("basePrompt").asText();
        assertThat(lessons.size()).isEqualTo(5);
        assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(evidence.path("validation"),5)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        var client=mock(OpenAiResponsesClient.class);var calls=new ArrayList<JsonNode>();var instructions=new ArrayList<String>();
        when(client.structured(anyString(),any(),anyMap())).thenAnswer(c->{instructions.add(c.getArgument(0));calls.add(json.valueToTree(c.getArgument(1,Object.class)));return evidence.path("draft");});
        var agent=new StyledLessonPromptAgent(client,json,new AiProperties(true,"test","gpt-5.6-luna",30));
        agent.compose(lessons,base,1000);agent.rewrite(lessons,base,1000,evidence.path("draft"),evidence.path("validation"));
        for(var sent:calls) {
            assertThat(sent.path("basePrompt").asText()).isEqualTo(base);
            assertThat(sent.path("rules").valueStream().map(l->l.path("prevention").asText()).toList())
                .containsExactlyElementsOf(lessons.valueStream().map(l->l.path("prevention").asText()).toList());
        }
        assertThat(instructions.getFirst()).contains("SAME modifier and scope","Never invent","Do not select a subset");
        assertThat(calls.get(1).path("semanticFeedback")).isEqualTo(evidence.path("validation"));
        assertThat(calls.get(1).path("revisionRequired").asText()).contains("Remove addedRequirements","Resolve conflictingRequirements","legacy missingBaseConditions","not every phrase");
    }

    @Test void semanticCorrectionSurvivesAFormatRetry()throws Exception {
        var evidence=fixture();var input=evidence.path("input");var client=mock(OpenAiResponsesClient.class);var calls=new ArrayList<JsonNode>();
        when(client.structured(anyString(),any(),anyMap())).thenAnswer(c->{calls.add(json.valueToTree(c.getArgument(1,Object.class)));
            return calls.size()==1?json.valueToTree(Map.of("prompt","Keep still\u2014no invented conditions.")):evidence.path("draft");});
        new StyledLessonPromptAgent(client,json,new AiProperties(true,"test","gpt-5.6-luna",30))
            .rewrite(input.path("lessons"),input.path("basePrompt").asText(),1000,evidence.path("draft"),evidence.path("validation"));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).path("revisionRequired").asText()).contains("formatErrors","Remove addedRequirements","Resolve conflictingRequirements");
        assertThat(calls.get(1).path("semanticFeedback")).isEqualTo(evidence.path("validation"));
    }

    @Test void independentAuditSeparatesMissingAddedAndConflictingClauses()throws Exception {
        var evidence=fixture();var input=evidence.path("input");var client=mock(OpenAiResponsesClient.class);
        when(client.structured(anyString(),any(),anyMap())).thenReturn(accepted());
        new StyledLessonPromptAgent(client,json,new AiProperties(true,"test","gpt-5.6-luna",30))
            .verify(input.path("lessons"),input.path("basePrompt").asText(),evidence.path("draft"),1000);
        var instructions=org.mockito.ArgumentCaptor.forClass(String.class);var schema=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).structured(instructions.capture(),any(),schema.capture());
        assertThat(instructions.getValue()).contains("unsupported by BOTH","ALL omissions, additions and contradictions","Never put an added requirement in missingBaseConditions");
        var sent=json.valueToTree(schema.getValue());
        assertThat(sent.path("required").valueStream().map(JsonNode::asText).toList()).contains("addedRequirements","conflictingRequirements");
    }

    @Test void optimisticAuditCannotReplaceRestorationWithMerelyHoldingStill()throws Exception {
        var evidence=fixture();var input=evidence.path("input");var client=mock(OpenAiResponsesClient.class);
        when(client.structured(anyString(),any(),anyMap())).thenReturn(accepted());
        var observed=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-scope-v5/live-false-positive.json")));
        var draft=observed.path("draft");
        var agent=new StyledLessonPromptAgent(client,json,new AiProperties(true,"test","gpt-5.6-luna",30));
        var checked=agent.verify(input.path("lessons"),input.path("basePrompt").asText(),draft,1000);
        assertThat(checked.path("modelBasePreserved").asBoolean()).isTrue();
        assertThat(checked.path("missingLiteralAnchors").valueStream().map(JsonNode::asText).toList()).containsExactly("Restore shared tail height");
        assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(checked,5)).hasMessage("LESSON_PROMPT_MEANING_LOST");
        var restored=json.valueToTree(Map.of("prompt",draft.path("prompt").asText()+" Restore shared tail height."));
        StyledLessonPromptAgent.checkVerdict(agent.verify(input.path("lessons"),input.path("basePrompt").asText(),restored,1000),5);
        // Source-derived, not tied to this dog, direction or tail rule.
        var other=agent.verify(input.path("lessons"),"Restore the original paw spacing; keep still.",draft,1000);
        assertThat(other.path("missingLiteralAnchors").valueStream().map(JsonNode::asText).toList()).containsExactly("Restore the original paw spacing");
    }

    @Test void additionsAndConflictsStillBlockWithOptimisticBooleansAndNeverBecomeReady()throws Exception {
        var evidence=fixture();var sources=evidence.at("/input/lessons");
        for(String field:List.of("addedRequirements","conflictingRequirements")) {
            for(JsonNode value:List.of(json.createArrayNode().add("Unsupported restriction."),json.valueToTree("invalid"),json.nullNode())) {
                var verdict=(tools.jackson.databind.node.ObjectNode)accepted();verdict.set(field,value);
                assertThatThrownBy(()->StyledLessonPromptAgent.checkVerdict(verdict,5)).hasMessage("LESSON_PROMPT_MEANING_LOST");
            }
            var verdict=(tools.jackson.databind.node.ObjectNode)accepted();verdict.putArray(field).add("Unsupported restriction.");
            var store=mock(StyledLessonPromptStore.class);var agent=mock(StyledLessonPromptAgent.class);
            when(store.start(any(),any(),anyString(),anyInt())).thenReturn(new StyledLessonPromptStore.Entry("key","COMPOSING",null,null,true));
            when(agent.compose(any(),anyString(),anyInt())).thenReturn(evidence.path("draft"));
            when(agent.rewrite(any(),anyString(),anyInt(),any(),any())).thenReturn(evidence.path("draft"));
            when(agent.verify(any(),anyString(),any(),anyInt())).thenReturn(verdict);
            assertThatThrownBy(()->new StyledLessonPromptComposer(store,agent).describe(null,sources,evidence.at("/input/basePrompt").asText(),1000))
                .hasMessage("LESSON_PROMPT_MEANING_LOST");
            verify(agent,times(3)).verify(any(),anyString(),any(),anyInt());
            verify(store,never()).ready(any(),any(),anyString(),any(),anyString());
        }
        // Already validated legacy receipts remain readable; new model calls use the stricter required schema.
        var legacy=(tools.jackson.databind.node.ObjectNode)accepted();legacy.remove(List.of("addedRequirements","conflictingRequirements"));
        StyledLessonPromptAgent.checkVerdict(legacy,5);
    }
}
