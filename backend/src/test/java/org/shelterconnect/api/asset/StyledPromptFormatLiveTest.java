package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in text-only Luna verification; never buys images or changes a deployed job. */
class StyledPromptFormatLiveTest {
    @Test void exactUnderBudgetFailureAndInitialGenerationPassIndependentReview() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("PROMPT_FORMAT_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("PROMPT_FORMAT_LIVE_OUTPUT"));Files.createDirectories(output);
        var fixture=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/full-prompt-budget-v2/evidence.json")));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        for(String name:List.of("firstGeneration","failed")) {
            var source=name.equals("failed")?json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/prompt-format-v3/evidence.json"))).path("input"):fixture.path(name).path("input");
            int budget=name.equals("failed")?2000:1000;
            var folder=output.resolve(name);Files.createDirectories(folder);var sequence=new java.util.concurrent.atomic.AtomicInteger();
            var client=new OpenAiResponsesClient(ai,json) {
                @Override public JsonNode structured(String instructions,Object input,Map<String,Object> schema) {
                    try {
                        int n=sequence.incrementAndGet();var intent=folder.resolve(n+"-intent.json");var receipt=folder.resolve(n+"-receipt.json");
                        var binding=json.valueToTree(Map.of("version",StyledLessonPromptAgent.VERSION,"model",ai.model(),"instructions",instructions,"input",input,"schema",schema));
                        if(Files.exists(receipt)) {assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);return json.readTree(Files.readAllBytes(receipt));}
                        assertThat(Files.exists(intent)).as("Never repeat a call with an unknown paid outcome").isFalse();
                        Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
                        var result=super.structured(instructions,input,schema);Files.write(receipt,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);return result;
                    } catch(java.io.IOException e) {throw new IllegalStateException(e);}
                }
            };
            var lessons=source.path("lessons");String base=source.path("basePrompt").asText();
            var store=mock(StyledLessonPromptStore.class);var history=json.createArrayNode();
            when(store.start(isNull(),eq(lessons),eq(base),eq(budget))).thenReturn(new StyledLessonPromptStore.Entry("live","COMPOSING",null,null,true));
            doAnswer(c->{history.addObject().set("draft",c.getArgument(3,JsonNode.class));return null;}).when(store).draft(any(),any(),anyString(),any());
            doAnswer(c->{history.addObject().set("validation",c.getArgument(3,JsonNode.class));return null;}).when(store).verdict(any(),any(),anyString(),any());
            String description=new StyledLessonPromptComposer(store,new StyledLessonPromptAgent(client,json,ai)).describe(null,lessons,base,budget);
            var verdict=history.get(history.size()-1).path("validation");StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());
            assertThat(description.length()).isLessThanOrEqualTo(budget);assertThat(lessons.size()).isEqualTo(name.equals("failed")?2:4);
            Files.write(folder.resolve("result.json"),json.writeValueAsBytes(Map.of("description",description,"characters",description.length(),"sourceRuleCount",lessons.size(),"history",history,"calls",sequence.get())));
            verify(store).ready(isNull(),eq(lessons),eq("live"),eq(verdict),eq(description));
        }
    }
}
