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
}
