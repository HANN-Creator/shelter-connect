package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in observation of already generated images. No PixelLab call, approval or DB mutation. */
class StyledVisualWarningsLiveTest {
    @Test void observeStoredNineFrameClipOnce()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("VISUAL_WARNINGS_LIVE_APPROVED")));
        var json=new JsonMapper();var input=json.readTree(Files.readAllBytes(Path.of(System.getenv("VISUAL_WARNINGS_LIVE_INPUT"))));
        var output=Path.of(System.getenv("VISUAL_WARNINGS_LIVE_OUTPUT"));Files.createDirectories(output);
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(StyledSpriteCodec.paddedSeed(Files.readAllBytes(Path.of(input.path(d).asText()))));
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(Path.of(input.path("sheet").asText())));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),System.getenv("OPENAI_MODEL"),60);
        var client=new OpenAiResponsesClient(ai,json) {
            @Override public JsonNode structuredImage(String instructions,String task,byte[] png,Map<String,Object> schema) {
                try {
                    var intent=output.resolve("intent.json");var receipt=output.resolve("receipt.json");
                    var binding=json.valueToTree(Map.of("version",StyledVisualWarnings.VERSION,"model",ai.model(),"instructions",instructions,"task",task,"boardSha256",StyledSpriteCodec.sha(png),"schema",schema));
                    if(Files.exists(receipt)){assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);return json.readTree(Files.readAllBytes(receipt));}
                    assertThat(Files.exists(intent)).as("Do not repeat an unknown paid observation").isFalse();
                    Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);Files.write(output.resolve("observation-board.png"),png);
                    var result=super.structuredImage(instructions,task,png,schema);Files.write(receipt,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);return result;
                }catch(java.io.IOException e){throw new IllegalStateException(e);}
            }
        };
        var observation=new StyledVisualWarningAgent(client,ai,json).observe(seeds,frames,input.path("action").asText(),input.path("direction").asText());
        var report=StyledVisualWarnings.report(json,observation,null);report.put("pixelLabRequests",0).put("productionChanged",false);
        report.set("frameHashes",json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
        Files.write(output.resolve("report.json"),json.writeValueAsBytes(report));assertThat(report.path("enforcement").asText()).isEqualTo("WARN_ONLY");
    }
}
