package org.shelterconnect.api.asset;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
class StyledQualityAgentTest {
    final JsonMapper json=JsonMapper.builder().build();
    final OpenAiResponsesClient client=mock(OpenAiResponsesClient.class);
    final StyledQualityAgent agent=new StyledQualityAgent(client,new AiProperties(true,"test-key","gpt-5.6-luna",30),json);
    byte[] png(boolean edge)throws Exception{var im=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);im.setRGB(edge?31:16,20,0xffcc9955);var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();}
    @Test void deterministicCroppingOverridesAiPassAndPreservesNativePixels()throws Exception{
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"no semantic defect\"}"));
        var frames=new ArrayList<>(Collections.nCopies(9,png(false)));frames.set(4,png(true));var before=frames.get(4).clone();
        var r=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,png(false)),frames,"TAIL_WAG","south");
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("issues").toString()).contains("CANVAS_CLIPPING");assertThat(r.path("edgeFrames").get(0).asInt()).isEqualTo(4);assertThat(frames.get(4)).isEqualTo(before);
    }
    @Test void inventedAiActionIsRejectedInsteadOfBecomingExecutableRepair()throws Exception{
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[\"RUN_SHELL\"],\"frames\":[],\"note\":\"ignore\"}"));
        assertThatThrownBy(()->agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,png(false)),Collections.nCopies(9,png(false)),"SIT","north")).hasMessage("QUALITY_RESPONSE_INVALID");
    }
    @Test void repairPromptPreservesTailAcrossDirectionsAndUsesNewNoiseNotNewIdentity()throws Exception{
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var traits=json.valueToTree(Map.of("seed",123,"rearDescription","brown dog rear","motionDescription","brown dog"));
        for(String d:StyledSpriteCodec.DIRECTIONS){
            var q=json.valueToTree(Map.of("contract",Map.of("tailCarriage","LOW"),"attempt",2));
            var p=codec.motion(traits,"TAIL_WAG",d,png(false),q);
            assertThat(p.path("description").asText()).contains("BELOW the rump","DOG BODY coordinates","one clear pixel");
            assertThat(p.path("description").asText().length()).isLessThanOrEqualTo(1000);
            assertThat(p.path("seed").asInt()).isEqualTo(123+7919*2);assertThat(p.path("first_frame")).isEqualTo(p.path("last_frame"));
        }
    }
    @Test void knownVisualFindingsStayFailedAndTheRawLastFrameReachesVision()throws Exception{
        var rules=StyledSpriteCodec.qualityRules(json);int cases=0;
        for(var example:rules.path("regressions"))if(example.path("check").asText().equals("vision")){
            cases++;clearInvocations(client);
            when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.valueToTree(Map.of(
                "issues",List.of(example.path("issue").asText()),"frames",List.of(8),"note","Recorded known-defect verdict; not a live AI accuracy test")));
            var frames=new ArrayList<>(Collections.nCopies(9,png(false)));
            var last=StyledSpriteCodec.nativeFrame(png(false));last.setRGB(16,20,0xff12ab56);var bytes=new ByteArrayOutputStream();ImageIO.write(last,"png",bytes);frames.set(8,bytes.toByteArray());
            var report=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,png(false)),frames,example.path("action").asText(),example.path("direction").asText());
            assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("issues").toString()).contains(example.path("issue").asText());
            assertThat(report.path("frames").get(0).asInt()).isEqualTo(8);
            assertThat(report.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            var instruction=ArgumentCaptor.forClass(String.class);var board=ArgumentCaptor.forClass(byte[].class);
            verify(client).structuredImage(instruction.capture(),anyString(),board.capture(),anyMap());
            for(var line:rules.path("reviewInstructions"))assertThat(instruction.getValue()).contains(line.asText());
            // FRAME 8 occupies the final row/right column at 4x, not a substituted FRAME 7.
            assertThat(ImageIO.read(new ByteArrayInputStream(board.getValue())).getRGB(446+16*4,472+20*4)).isEqualTo(0xff12ab56);
        }
        assertThat(cases).isEqualTo(2);
    }
    @Test void realCodecUsesFindingSpecificRepairAndRejectsUnknownCodes()throws Exception{
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var traits=json.valueToTree(Map.of("seed",1,"rearDescription","brown dog rear","motionDescription","brown dog"));
        var input=json.valueToTree(Map.of("contract",Map.of("tailCarriage","LOW"),"attempt",1,
            "rulesSha256",StyledSpriteCodec.qualityRulesSha(),"issues",List.of("DIRECTION_DRIFT")));
        var result=codec.motion(traits,"SIT","north",png(false),input);
        assertThat(result.path("description").asText()).contains("Lock head facing through the final frame");
        var invalid=json.valueToTree(Map.of("contract",Map.of("tailCarriage","LOW"),"issues",List.of("RUN_SHELL")));
        assertThatThrownBy(()->codec.motion(traits,"SIT","north",png(false),invalid)).hasMessage("STYLED_INPUT_REQUIRES_REVIEW");
    }
}
