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
    @Test void realIdleDefectsOverrideVisionPassAndUseTheCorrectDirectionSeed()throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/fixtures/idle-motion-alpha.json")));
        var expected=Map.of("south",List.of(1,2,3,6,7,8),"north",List.of(4,5,6),"west",List.of(1,2,3,4,5,6,7),"east",List.of(2,4,5,6,7));
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"Recorded AI pass, deterministic gate must override\"}"));
        var directions=List.of("south","north","west","east");var clips=new LinkedHashMap<String,List<byte[]>>();
        for(String direction:directions) {
            var frames=new ArrayList<byte[]>();
            for(var rows:fixture.path("clips").path(direction).path("frames")) {
                var frame=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<32;y++)for(int x=0;x<32;x++)
                    if((Long.parseLong(rows.get(y).asText(),16)&(1L<<(31-x)))!=0)frame.setRGB(x,y,0xff464646);
                var out=new ByteArrayOutputStream();ImageIO.write(frame,"png",out);frames.add(out.toByteArray());
            }
            clips.put(direction,frames);
        }
        var seeds=directions.stream().map(d->clips.get(d).getFirst()).toList();
        for(String direction:directions) {
            var frames=clips.get(direction);var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();
            var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,frames,"IDLE",direction);
            assertThat(report.path("passed").asBoolean()).isFalse();
            assertThat(report.path("issues").toString()).contains("IDLE_MOTION");
            assertThat(report.path("idleMotionFrames")).isEqualTo(json.valueToTree(expected.get(direction)));
            assertThat(StyledQualityAgent.idleMotionFrames(seeds.get(directions.indexOf(direction)),frames,"TAIL_WAG",StyledSpriteCodec.qualityRules(json).path("idleMotion"))).isEmpty();
            assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        }
    }
    @Test void idleVisionCanRejectMotionInsideTheSilhouetteButCannotFreezeWalking()throws Exception {
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[\"IDLE_MOTION\"],\"frames\":[2,3],\"note\":\"Tail swishes inside body silhouette\"}"));
        var seed=png(false);var frames=Collections.nCopies(9,seed);
        var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,seed),frames,"IDLE","north");
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("idleMotionFrames").isEmpty()).isTrue();
        assertThatThrownBy(()->agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,seed),frames,"WALK","north")).hasMessage("QUALITY_RESPONSE_INVALID");
    }
    @Test void onePixelIdleBreathingIsAllowed()throws Exception {
        var seed=StyledSpriteCodec.nativeFrame(silhouette(-1));var shifted=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        var g=shifted.createGraphics();g.drawImage(seed,0,-1,null);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(shifted,"png",out);
        var frames=new ArrayList<>(Collections.nCopies(9,silhouette(-1)));frames.set(4,out.toByteArray());
        assertThat(StyledQualityAgent.idleMotionFrames(silhouette(-1),frames,"IDLE",StyledSpriteCodec.qualityRules(json).path("idleMotion"))).isEmpty();
    }
    byte[] silhouette(int extensionY)throws Exception{
        var im=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        var g=im.createGraphics();g.setColor(new java.awt.Color(138,87,55));
        g.fillRect(9,4,13,11);g.fillRect(7,14,18,11);g.fillRect(9,23,4,7);g.fillRect(20,23,4,7);
        if(extensionY>=0)g.fillRect(3,extensionY,6,3);
        g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();
    }
    @Test void realFrontalSilhouetteGateOverridesWrongAiPassAndExcludesBowingAndRaisedTails()throws Exception{
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"AI missed the high tail\"}"));
        var seed=silhouette(-1);var frames=new ArrayList<>(Collections.nCopies(9,seed));frames.set(4,silhouette(12));
        byte[] before=frames.get(4).clone();
        var r=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,seed),frames,"TAIL_WAG","south");
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("issues").toString()).isEqualTo("[\"TAIL_CARRIAGE\"]");
        assertThat(r.path("silhouetteFrames").toString()).isEqualTo("[4]");assertThat(frames.get(4)).isEqualTo(before);
        assertThat(agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,seed),frames,"SNIFF","south").path("passed").asBoolean()).isTrue();
        assertThat(agent.review(json.readTree("{\"tailCarriage\":\"HIGH\"}"),Collections.nCopies(4,seed),frames,"TAIL_WAG","south").path("passed").asBoolean()).isTrue();
        frames.set(4,silhouette(25));
        assertThat(agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,seed),frames,"TAIL_WAG","south").path("passed").asBoolean()).isTrue();
    }
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
    @Test void floatingTailPixelOverridesAiPassWithoutChangingAnyPixel() throws Exception {
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"missed fragment\"}"));
        byte[] seed=silhouette(-1);var image=StyledSpriteCodec.nativeFrame(seed);image.setRGB(2,28,0xff123456);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);var frames=new ArrayList<>(Collections.nCopies(9,seed));frames.set(8,out.toByteArray());
        var report=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,seed),frames,"TAIL_WAG","west");
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("detachedFrames").toString()).isEqualTo("[8]");
        assertThat(report.path("issues").toString()).contains("DETACHED_PIXELS");assertThat(frames.get(8)).isEqualTo(out.toByteArray());
    }
}
