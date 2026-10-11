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
class StyledFullPromptLiveTest {
    @Test void exactInitialAndRepairDescriptionsPreserveAllRequirementsWithinProviderLimit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("FULL_PROMPT_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("FULL_PROMPT_LIVE_OUTPUT"));Files.createDirectories(output);
        var fixture=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/full-prompt-budget-v2/evidence.json")));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        for(String name:List.of("firstGeneration","failed")) {
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
            var source=fixture.path(name).path("input");var lessons=source.path("lessons");String base=source.path("basePrompt").asText();
            var store=mock(StyledLessonPromptStore.class);var history=json.createArrayNode();
            when(store.start(isNull(),eq(lessons),eq(base),eq(1000))).thenReturn(new StyledLessonPromptStore.Entry("live","COMPOSING",null,null,true));
            doAnswer(c->{history.addObject().set("draft",c.getArgument(3,JsonNode.class));return null;}).when(store).draft(any(),any(),anyString(),any());
            doAnswer(c->{history.addObject().set("validation",c.getArgument(3,JsonNode.class));return null;}).when(store).verdict(any(),any(),anyString(),any());
            String description=new StyledLessonPromptComposer(store,new StyledLessonPromptAgent(client,json,ai)).describe(null,lessons,base,1000);
            var verdict=history.get(history.size()-1).path("validation");StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());
            assertThat(description.length()).isLessThanOrEqualTo(1000);assertThat(lessons.size()).isEqualTo(4);
            Files.write(folder.resolve("result.json"),json.writeValueAsBytes(Map.of("description",description,"characters",description.length(),"sourceRuleCount",lessons.size(),"history",history,"calls",sequence.get())));
            verify(store).ready(isNull(),eq(lessons),eq("live"),eq(verdict),eq(description));
        }
    }
}
