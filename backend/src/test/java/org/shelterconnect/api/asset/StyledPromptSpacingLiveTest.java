package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in verification of the saved completed drafts. No PixelLab calls or deployed job changes. */
class StyledPromptSpacingLiveTest {
    @Test void actualOverBudgetDraftRecoveryPassesIndependentLunaReview()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("PROMPT_SPACING_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("PROMPT_SPACING_LIVE_OUTPUT"));Files.createDirectories(output);
        var evidence=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-spacing-v4/evidence.json")));
        var input=evidence.path("input");var lessons=input.path("lessons");String base=input.path("basePrompt").asText();int budget=input.path("budget").asInt();
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),System.getenv("OPENAI_MODEL"),60);
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var client=new OpenAiResponsesClient(ai,json) {
            @Override public JsonNode structured(String instructions,Object input,Map<String,Object> schema) {
                try {
                    int n=sequence.incrementAndGet();var intent=output.resolve(n+"-intent.json");var receipt=output.resolve(n+"-receipt.json");
                    var binding=json.valueToTree(Map.of("version",StyledLessonPromptAgent.VERSION,"model",ai.model(),"instructions",instructions,"input",input,"schema",schema));
                    if(Files.exists(receipt)){assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);return json.readTree(Files.readAllBytes(receipt));}
                    assertThat(Files.exists(intent)).as("Never repeat a call with an unknown paid outcome").isFalse();
                    Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
                    var result=super.structured(instructions,input,schema);Files.write(receipt,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);return result;
                } catch(java.io.IOException e){throw new IllegalStateException(e);}
            }
        };
        var agent=spy(new StyledLessonPromptAgent(client,json,ai));
        doThrow(new AssetException(409,"LESSON_PROMPT_INVALID",evidence.at("/history/0"))).when(agent).compose(lessons,base,budget);
        var store=mock(StyledLessonPromptStore.class);var history=json.createArrayNode();
        when(store.start(isNull(),eq(lessons),eq(base),eq(budget))).thenReturn(new StyledLessonPromptStore.Entry("live","COMPOSING",null,null,true));
        doAnswer(c->{history.add(c.getArgument(3,JsonNode.class));return null;}).when(store).diagnostics(any(),any(),anyString(),any());
        doAnswer(c->{history.addObject().set("draft",c.getArgument(3,JsonNode.class));return null;}).when(store).draft(any(),any(),anyString(),any());
        doAnswer(c->{history.addObject().set("validation",c.getArgument(3,JsonNode.class));return null;}).when(store).verdict(any(),any(),anyString(),any());
        try {
            String description=new StyledLessonPromptComposer(store,agent).describe(null,lessons,base,budget);
            assertThat(description.length()).isLessThanOrEqualTo(budget);var verdict=history.get(history.size()-1).path("validation");
            StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());
            Files.write(output.resolve("result.json"),json.writeValueAsBytes(Map.of("version",StyledLessonPromptAgent.VERSION,"model",ai.model(),"description",description,"characters",description.length(),"sourceRuleCount",lessons.size(),"calls",sequence.get(),"passed",true)));
        } finally {Files.write(output.resolve("history.json"),json.writeValueAsBytes(history));}
    }
}
