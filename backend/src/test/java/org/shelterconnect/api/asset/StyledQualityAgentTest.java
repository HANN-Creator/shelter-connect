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
    @Test void liveAuthorizedOshuMotionReview()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("OSHU_MOTION_LIVE")));
        var properties=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var live=new StyledQualityAgent(new OpenAiResponsesClient(properties,json),properties,json);
        var root=java.nio.file.Path.of("scripts/fixtures/oshu-motion-learning-v13");var seeds=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(java.nio.file.Files.readAllBytes(root.resolve("directions/"+d+".png")));
        var output=java.nio.file.Path.of(System.getenv("OSHU_MOTION_REPORT"));java.nio.file.Files.createDirectories(output);
        for(String action:List.of("IDLE","WALK","SIT"))for(String d:StyledSpriteCodec.DIRECTIONS) {
            String label=action.toLowerCase()+"-"+d;
            if(java.nio.file.Files.exists(output.resolve(label+".json")))continue;
            byte[] source=java.nio.file.Files.readAllBytes(root.resolve("sheets/"+label+".png"));
            var result=live.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,StyledSpriteCodec.frames(source),action,d);
            ((tools.jackson.databind.node.ObjectNode)result).put("inputSha256",StyledSpriteCodec.sha(source));
            java.nio.file.Files.write(output.resolve(label+".json"),json.writeValueAsBytes(result));
            System.out.println(label+": "+result.path("issues"));
        }
    }
    @Test void smallRgbOnlyIdleGetsOneIndependentCheckButOtherDefectsAndUncertaintyRemain()throws Exception {
        var seed=png(false);var im=StyledSpriteCodec.nativeFrame(seed);int old=im.getRGB(16,20);im.setRGB(16,20,old^0x00010101);
        var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);var frames=new ArrayList<>(Collections.nCopies(9,seed));frames.set(8,out.toByteArray());
        for(String decision:List.of("SHADING_ONLY","VISIBLE_MOTION","UNCERTAIN"))for(boolean identity:List.of(false,true)) {
            reset(client);
            var first=json.valueToTree(Map.of("issues",identity?List.of("IDLE_MOTION","IDENTITY_DRIFT"):List.of("IDLE_MOTION"),"frames",List.of(8),"note","Initial simulated motion finding"));
            var second=json.valueToTree(Map.of("classification",decision,"issues",List.of(),"frames",List.of(),"note","Independent synthetic classification, not a live accuracy result"));
            when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(first,second);
            var r=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),Collections.nCopies(4,seed),frames,"IDLE","south");
            assertThat(r.path("passed").asBoolean()).isEqualTo(decision.equals("SHADING_ONLY") && !identity);
            assertThat(r.path("initialVision")).isEqualTo(first);assertThat(r.path("consistencyReview")).isEqualTo(second);
            var tasks=ArgumentCaptor.forClass(String.class);verify(client,times(2)).structuredImage(anyString(),tasks.capture(),any(),anyMap());
            assertThat(tasks.getAllValues().get(1)).doesNotContain("Initial simulated motion finding");
        }
    }
    @Test void actualClippedWalkAndSitKeepLastFrameAndFailEvenWhenVisionPasses()throws Exception {
        var root=java.nio.file.Path.of("scripts/fixtures/oshu-motion-learning-v13");var seeds=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(java.nio.file.Files.readAllBytes(root.resolve("directions/"+d+".png")));
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"Synthetic vision pass; structural check must reject\"}"));
        for(String a:List.of("WALK","SIT"))for(String d:List.of("west","east")) {
            var frames=StyledSpriteCodec.frames(java.nio.file.Files.readAllBytes(root.resolve("sheets/"+a.toLowerCase()+"-"+d+".png")));
            var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();
            var r=agent.review(json.readTree("{\"tailCarriage\":\"LOW\"}"),seeds,frames,a,d);
            assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("issues").toString()).contains("CANVAS_CLIPPING");
            assertThat(frames).hasSize(9);assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        }
    }
    final JsonMapper json=JsonMapper.builder().build();
    final OpenAiResponsesClient client=mock(OpenAiResponsesClient.class);
    final StyledQualityAgent agent=new StyledQualityAgent(client,new AiProperties(true,"test-key","gpt-5.6-luna",30),json);
    @Test void actualIdlePixelEvidenceMatchesCliAndIsSentToVisionWithoutOverridingItsVerdict()throws Exception {
        var root=java.nio.file.Path.of("scripts/fixtures");
        var fixture=json.readTree(java.nio.file.Files.readString(root.resolve("idle-review-evidence.json")));
        assertThat(fixture.path("originalQualityReport").path("passed").asBoolean()).isFalse();
        var modelFailure=json.readTree("{\"issues\":[\"IDENTITY_DRIFT\"],\"frames\":[6],\"note\":\"Independent eye or internal detail failure must not be bypassed by stable alpha\"}");
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(modelFailure);
        for(String name:List.of("raw","candidate")) {
            var record=fixture.path("clips").path(name);byte[] sheet=java.nio.file.Files.readAllBytes(root.resolve(record.path("file").asText()));
            assertThat(StyledSpriteCodec.sha(sheet)).isEqualTo(record.path("sha256").asText());
            var image=ImageIO.read(new ByteArrayInputStream(sheet));var frames=new ArrayList<byte[]>();
            for(int i=0;i<9;i++) {var out=new ByteArrayOutputStream();ImageIO.write(image.getSubimage(i*32,0,32,32),"png",out);frames.add(out.toByteArray());}
            var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();
            var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,frames.getFirst()),frames,"IDLE","north");
            assertThat(report.path("pixelEvidence")).isEqualTo(record.path("pixelEvidence"));
            assertThat(report.path("pixelEvidence").path("subtleShadingOnly").asBoolean()).isTrue();
            assertThat(report.path("reviewLayout").asText()).isEqualTo("matched-direction-frame-pairs-v1");
            assertThat(report.path("passed").asBoolean()).isFalse();
            assertThat(report.path("issues").toString()).contains("IDENTITY_DRIFT");
            assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        }
        var tasks=ArgumentCaptor.forClass(String.class);var instructions=ArgumentCaptor.forClass(String.class);
        verify(client,times(2)).structuredImage(instructions.capture(),tasks.capture(),any(),anyMap());
        assertThat(tasks.getAllValues()).allSatisfy(t->assertThat(t).contains("\"alphaStable\":true","\"alphaChangedFromFrame0\":[0,0,0,0,0,0,0,0,0]","NOT animation frames"));
        assertThat(instructions.getAllValues()).allSatisfy(t->assertThat(t).contains("do NOT prove identity","Never suppress clipping","false subtleShadingOnly value is NOT a defect verdict"));
    }
    @Test void highContrastDetailsAndSameBoundsAlphaChangesAreNotSubtleShading()throws Exception {
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=5;y<27;y++)for(int x=8;x<24;x++)image.setRGB(x,y,0xff464646);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);byte[] seed=bytes.toByteArray();
        var frames=new ArrayList<>(Collections.nCopies(9,seed));image.setRGB(10,10,0xffffffff);
        bytes.reset();ImageIO.write(image,"png",bytes);frames.set(8,bytes.toByteArray());
        var evidence=StyledQualityAgent.pixelEvidence(frames,StyledSpriteCodec.qualityRules(json),json);
        assertThat(evidence.path("alphaStable").asBoolean()).isTrue();
        assertThat(evidence.path("subtleShadingOnly").asBoolean()).isFalse();
        image.setRGB(10,10,0);bytes.reset();ImageIO.write(image,"png",bytes);frames.set(8,bytes.toByteArray());
        evidence=StyledQualityAgent.pixelEvidence(frames,StyledSpriteCodec.qualityRules(json),json);
        assertThat(evidence.path("boundsExclusive").get(0)).isEqualTo(evidence.path("boundsExclusive").get(8));
        assertThat(evidence.path("alphaChangedFromFrame0").get(8).asInt()).isEqualTo(1);
        assertThat(evidence.path("subtleShadingOnly").asBoolean()).isFalse();
    }
    @Test void actualSeatedTailClippingOverridesVisionPassIncludingTheFinalHold()throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/fixtures/sit-tail-alpha.json")));
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"Mock pass to exercise deterministic border gate, not live visual accuracy\"}"));
        var clips=new LinkedHashMap<String,List<byte[]>>();
        for(String name:List.of("original","corrected")) {
            var frames=new ArrayList<byte[]>();
            for(var rows:fixture.path("clips").path(name).path("frames")) {
                var frame=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<32;y++)for(int x=0;x<32;x++)
                    if((Long.parseLong(rows.get(y).asText(),16)&(1L<<(31-x)))!=0)frame.setRGB(x,y,0xff464646);
                var out=new ByteArrayOutputStream();ImageIO.write(frame,"png",out);frames.add(out.toByteArray());
            }
            assertThat(frames).hasSize(9);
            var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();
            var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,frames.getFirst()),frames,"SIT","west");
            assertThat(report.path("passed").asBoolean()).isEqualTo(name.equals("corrected"));
            assertThat(report.path("edgeFrames")).isEqualTo(json.valueToTree(name.equals("original")?List.of(3,4,5,6,7,8):List.of()));
            if(name.equals("original"))assertThat(report.path("issues").toString()).contains("CANVAS_CLIPPING");
            assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
            clips.put(name,frames);
        }
        var lastBad=new ArrayList<>(clips.get("corrected"));lastBad.set(8,clips.get("original").get(8));
        var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,lastBad.getFirst()),lastBad,"SIT","west");
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("edgeFrames").toString()).isEqualTo("[8]");
    }
    @Test void serverCodecKeepsFullSeatedTailPreventionOnInitialAndRepairRequests()throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var traits=json.valueToTree(Map.of("seed",123,"rearDescription","black dog rear","motionDescription","black dog"));
        for(int attempt:List.of(0,1,2)) {
            var quality=json.valueToTree(Map.of("contract",Map.of("tailCarriage","UNKNOWN"),"attempt",attempt,
                "rulesSha256",StyledSpriteCodec.qualityRulesSha(),"issues",attempt==0?List.of():List.of("CANVAS_CLIPPING")));
            var payload=codec.motion(traits,"SIT","west",png(false),quality);
            assertThat(payload.path("description").asText()).contains("Keep complete tail tucked by haunch INSIDE every frame");
            assertThat(payload.has("last_frame")).isFalse();
            assertThat(payload.path("description").asText().length()).isLessThanOrEqualTo(1000);
        }
    }
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
            // Moving actions keep all direction references and the original nine-frame temporal grid.
            assertThat(report.path("reviewLayout").asText()).isEqualTo("four-direction-temporal-grid-v1");
            assertThat(ImageIO.read(new ByteArrayInputStream(board.getValue())).getRGB(446+16*4,472+20*4)).isEqualTo(0xff12ab56);
        }
        assertThat(cases).isEqualTo(2);
    }
    @Test void pairedReviewShowsOnlyMatchingDirectionAndAllNineUnmodifiedFrames()throws Exception {
        var seeds=new ArrayList<byte[]>();var frames=new ArrayList<byte[]>();
        for(int i=0;i<13;i++) {
            var im=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            im.setRGB(0,0,0xff120000+i);im.setRGB(31,31,0xff340000+i);
            im.setRGB(15,15,0xff560000+i);
            var bytes=new ByteArrayOutputStream();ImageIO.write(im,"png",bytes);
            (i<4?seeds:frames).add(bytes.toByteArray());
        }
        var sourceHashes=frames.stream().map(StyledSpriteCodec::sha).toList();
        var directions=List.of("south","north","west","east");
        for(int direction=0;direction<4;direction++) {
            var board=ImageIO.read(new ByteArrayInputStream(StyledQualityAgent.pairedBoard(seeds,frames,directions.get(direction))));
            assertThat(board.getWidth()).isEqualTo(896);assertThat(board.getHeight()).isEqualTo(704);
            assertThat(board.getRGB(16,32)).isEqualTo(0xff120000+direction);
            assertThat(board.getRGB(16+127,32+127)).isEqualTo(0xff340000+direction);
            for(int f=0;f<9;f++) {
                int x=16+(f%3)*288,y=192+(f/3)*168;
                var reference=StyledSpriteCodec.nativeFrame(frames.getFirst());var current=StyledSpriteCodec.nativeFrame(frames.get(f));
                for(int py=0;py<32;py++)for(int px=0;px<32;px++)for(int sy=0;sy<4;sy++)for(int sx=0;sx<4;sx++) {
                    int expected=reference.getRGB(px,py);if((expected>>>24)==0)expected=0xffebede1;
                    assertThat(board.getRGB(x+px*4+sx,y+py*4+sy)).isEqualTo(expected);
                    expected=current.getRGB(px,py);if((expected>>>24)==0)expected=0xffebede1;
                    assertThat(board.getRGB(x+144+px*4+sx,y+py*4+sy)).isEqualTo(expected);
                }
            }
        }
        assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(sourceHashes);
        assertThatThrownBy(()->StyledQualityAgent.pairedBoard(seeds,frames,"unknown")).hasMessage("QUALITY_RESPONSE_INVALID");
    }
    @Test void onlyIdleUsesFirstFramePairsWhileMovingActionsRetainAllDirectionReferences()throws Exception {
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"issues\":[],\"frames\":[],\"note\":\"Mock response for layout routing only\"}"));
        var seed=png(false);var frames=Collections.nCopies(9,seed);
        for(String action:List.of("IDLE","WALK","RUN","SNIFF","TAIL_WAG","BACK_OFF","SIT","LIE_DOWN")) {
            clearInvocations(client);
            var report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,seed),frames,action,"west");
            var image=ArgumentCaptor.forClass(byte[].class);var task=ArgumentCaptor.forClass(String.class);var instruction=ArgumentCaptor.forClass(String.class);
            verify(client).structuredImage(instruction.capture(),task.capture(),image.capture(),anyMap());
            var board=ImageIO.read(new ByteArrayInputStream(image.getValue()));
            if(action.equals("IDLE")) {
                assertThat(report.path("reviewLayout").asText()).isEqualTo("matched-direction-frame-pairs-v1");
                assertThat(board.getWidth()).isEqualTo(896);assertThat(task.getValue()).contains("nine labeled pairs");
                assertThat(instruction.getValue()).contains("false subtleShadingOnly value is NOT a defect verdict");
            } else {
                assertThat(report.path("reviewLayout").asText()).isEqualTo("four-direction-temporal-grid-v1");
                assertThat(board.getWidth()).isEqualTo(640);assertThat(board.getHeight()).isEqualTo(608);
                assertThat(task.getValue()).contains("SOUTH, NORTH, WEST, EAST","neighboring frames").doesNotContain("nine labeled pairs");
                assertThat(instruction.getValue()).doesNotContain("false subtleShadingOnly value is NOT a defect verdict");
            }
        }
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
