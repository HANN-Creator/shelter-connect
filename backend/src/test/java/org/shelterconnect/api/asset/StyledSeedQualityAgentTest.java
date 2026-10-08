package org.shelterconnect.api.asset;

import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledSeedQualityAgentTest {
    @Test void unapprovedSeedAlignmentPreservesEveryVisiblePixelAndCannotShrinkAnOversizedDog()throws Exception {
        var im=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=3;y<31;y++)for(int x=6;x<26;x++)im.setRGB(x,y,0xff000000|(x*7<<16)|(y*6<<8)|33);
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(im,"png",out);
        var aligned=StyledSpriteCodec.alignSeed(out.toByteArray(),2);assertThat(aligned.dx()).isZero();assertThat(aligned.dy()).isEqualTo(-1);
        var result=StyledSpriteCodec.nativeFrame(aligned.image());int count=0;
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((im.getRGB(x,y)>>>24)!=0) {
            assertThat(result.getRGB(x,y-1)).isEqualTo(im.getRGB(x,y));count++;
        }
        int after=0;for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((result.getRGB(x,y)>>>24)!=0)after++;
        assertThat(after).isEqualTo(count);
        byte[] original=Files.readAllBytes(Path.of("scripts/fixtures/oshu-motion-learning-v13/directions/west.png"));
        assertThat(StyledSpriteCodec.alignSeed(original,2).image()).isSameAs(original);
    }
    @Test void actualOshuSeedClearanceFailsOnlyNewAnimationPolicyWithoutChangingPixels()throws Exception {
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(Path.of("scripts/fixtures/oshu-motion-learning-v13/directions/"+d+".png")));
        var hashes=seeds.stream().map(StyledSpriteCodec::sha).toList();
        var original=json.valueToTree(Map.of("passed",true,"issues",List.of()));
        assertThat(StyledSeedQualityAgent.motionMargin(original,seeds,json.createObjectNode(),json)).isSameAs(original);
        var checked=StyledSeedQualityAgent.motionMargin(original,seeds,json.readTree("{\"seedMotionMargin\":2}"),json);
        assertThat(checked.path("passed").asBoolean()).isFalse();assertThat(checked.path("marginDirections").size()).isEqualTo(4);
        assertThat(checked.path("issues").toString()).contains("SEED_MOTION_MARGIN");
        assertThat(checked.at("/clearPixelsLeftTopRightBottom/west").toString()).isEqualTo("[1,2,1,1]");
        assertThat(seeds.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
    }
    final JsonMapper json=JsonMapper.builder().build();
    final OpenAiResponsesClient client=mock(OpenAiResponsesClient.class);
    final StyledSeedQualityAgent agent=new StyledSeedQualityAgent(client,new AiProperties(true,"test-key","gpt-5.6-luna",30),json);
    @Test void liveApprovedSeedCasesAreJudgedIndependentlyWithoutExpectedLabels()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("SEED_EYE_LIVE_APPROVED")));
        String key=System.getenv("OPENAI_API_KEY");assertThat(key).isNotBlank();
        var properties=new AiProperties(true,key,"gpt-5.6-luna",120);
        var live=new StyledSeedQualityAgent(new OpenAiResponsesClient(properties,json),properties,json);
        var config=Path.of(System.getenv("SEED_EYE_LIVE_CASES"));var cases=json.readTree(Files.readAllBytes(config));
        assertThat(cases.isArray()).isTrue();assertThat(cases.size()).isBetween(2,4);
        var failures=new ArrayList<String>();int index=0;
        for(var c:cases) {
            var photo=Path.of(c.path("photo").asText());var directory=Path.of(c.path("seedsDirectory").asText());
            var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(directory.resolve(d+".png")));
            // Only these bytes reach the production agent. Expected outcomes and paths stay local.
            var report=live.review(Files.readAllBytes(photo),seeds);
            Files.write(config.resolveSibling("seed-live-"+index+".json"),json.writeValueAsBytes(report));
            if(report.path("passed").asBoolean()!=c.path("expectedPass").asBoolean())failures.add("case "+index+": "+report.path("issues"));
            index++;
        }
        assertThat(failures).isEmpty();
    }
    byte[] seed()throws Exception{return Files.readAllBytes(Path.of("asset-styles/cozy32-v1/style.png"));}
    JsonNode verdict(String direction,String result,String identity) {
        return json.valueToTree(Map.of("identity",identity,"note","Synthetic response; not live visual evidence",
            "views",StyledSpriteCodec.DIRECTIONS.stream().map(d->Map.of("direction",d,
                "readability",d.equals(direction)?result:d.equals("north")?"NOT_VISIBLE":"PASS",
                "style",d.equals(direction)?result:d.equals("north")?"NOT_VISIBLE":"PASS","note","Fixture verdict")).toList()));
    }
    @Test void allVisibleEyesMustPassAndUncertaintyNeverPasses()throws Exception {
        for(String direction:List.of("south","west","east"))for(String result:List.of("FAIL","UNCERTAIN","NOT_VISIBLE")) {
            when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(verdict(direction,result,"PASS"));
            var report=agent.review(seed(),Collections.nCopies(4,seed()));
            assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("issues").toString()).contains("EYE_READABILITY","EYE_STYLE");
        }
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(verdict("south","PASS","PASS"));
        assertThat(agent.review(seed(),Collections.nCopies(4,seed())).path("passed").asBoolean()).isTrue();
    }
    @Test void rearEyesAndIdentityDriftFailAndDuplicateDirectionsAreMalformed()throws Exception {
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(verdict("north","PASS","FAIL"));
        var r=agent.review(seed(),Collections.nCopies(4,seed()));
        assertThat(r.path("issues").toString()).contains("EYE_DIRECTION","SEED_IDENTITY");
        var duplicate=verdict("south","PASS","PASS");
        ((tools.jackson.databind.node.ObjectNode)duplicate.path("views").get(1)).put("direction","south");
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(duplicate);
        assertThatThrownBy(()->agent.review(seed(),Collections.nCopies(4,seed()))).hasMessage("QUALITY_SEED_RESPONSE_INVALID");
    }
    @Test void actualRearRenderingPassDoesNotInventVisibleEyes()throws Exception {
        var fixture=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/seed-eye-verdict-regressions.json")));
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(fixture.path("response"));
        var report=agent.review(seed(),Collections.nCopies(4,seed()));
        assertThat(report.path("issues").valueStream().map(JsonNode::asText)).containsExactly("EYE_READABILITY");
        assertThat(report.path("passed").asBoolean()).isFalse();
        // Synthetic all-visible-eyes-pass case checks interpretation, not actual image quality.
        var passing=verdict("south","PASS","PASS");
        ((tools.jackson.databind.node.ObjectNode)passing.path("views").get(1)).put("style","PASS");
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(passing);
        assertThat(agent.review(seed(),Collections.nCopies(4,seed())).path("passed").asBoolean()).isTrue();
        for(String invalidStyle:List.of("FAIL","UNCERTAIN")) {
            ((tools.jackson.databind.node.ObjectNode)passing.path("views").get(1)).put("style",invalidStyle);
            assertThat(agent.review(seed(),Collections.nCopies(4,seed())).path("issues").valueStream().map(JsonNode::asText))
                .containsExactly("EYE_STYLE");
        }
    }
    @Test void approvalIsBoundToAllFourBytesPolicyAndRules()throws Exception {
        var seeds=Collections.nCopies(4,seed());
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(verdict("south","PASS","PASS"));
        var r=agent.review(seed(),seeds);var hashes=json.createObjectNode();
        for(String d:StyledSpriteCodec.DIRECTIONS)hashes.put(d,StyledSpriteCodec.sha(seed()));
        var policy=json.valueToTree(Map.of("seedQualityVersion",StyledSeedQualityAgent.VERSION,"rulesSha256",StyledSpriteCodec.qualityRulesSha()));
        assertThat(StyledSeedQualityAgent.passed(r,hashes,policy)).isTrue();
        assertThat(StyledSeedQualityAgent.passed(null,hashes,policy)).isFalse();
        hashes.put("east","0".repeat(64));assertThat(StyledSeedQualityAgent.passed(r,hashes,policy)).isFalse();
        assertThat(StyledSeedQualityAgent.passed(null,hashes,json.createObjectNode())).isTrue();
    }
    @Test void serverSeedPayloadCarriesEyeRulesAndBoundedCorrectionsAtMaximumIdentityLength()throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var traits=json.valueToTree(Map.of("seed",100,"sourcePhotoSha256",StyledSpriteCodec.sha(seed()),"faceBox",List.of(0,0,1,1),
            "identityDescription","a".repeat(850),"motionDescription","same puppy","rearDescription","same puppy rear",
            "reviewNote","reviewed original photo and face crop"));
        for(int attempt=0;attempt<=2;attempt++) {
            var policy=json.valueToTree(Map.of("attempt",attempt,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"issues",List.of("EYE_READABILITY","SEED_MOTION_MARGIN")));
            var p=codec.character(UUID.randomUUID(),traits,seed(),policy);
            assertThat(p.path("description").asText()).contains("Soft filled dark pupils","No hollow eye rings");
            assertThat(p.path("description").asText().length()).isLessThanOrEqualTo(2000);
            assertThat(p.path("seed").asInt()).isEqualTo(100+7919*attempt);
        }
    }
    @Test void seedLessonsReachGenerationAndReviewWithoutOverridingHardChecks()throws Exception {
        var lesson=json.readTree("{\"id\":\"12345678-1234-1234-1234-123456789abc\",\"sha256\":\""+"a".repeat(64)+"\",\"action\":\"BASE\",\"direction\":\"all\",\"tail\":\"UNKNOWN\",\"issue\":\"EYE_READABILITY\",\"prevention\":\"Keep compact filled pupils distinct from surrounding fur.\",\"criterion\":\"A front or side pupil disappears into surrounding fur.\"}");
        ((tools.jackson.databind.node.ObjectNode)lesson).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        var lessons=json.createArrayNode();for(int i=0;i<5;i++)lessons.add(lesson);
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(verdict("south","PASS","PASS"));
        var clipped=new StyledQualityAgentTest().png(true);
        var report=agent.review(seed(),Collections.nCopies(4,clipped),lessons);
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("issues").toString()).contains("CANVAS_CLIPPING");
        assertThat(report.path("learnedLessons")).isEqualTo(lessons);assertThat(report.path("photoSha256").asText()).isEqualTo(StyledSpriteCodec.sha(seed()));
        var task=org.mockito.ArgumentCaptor.forClass(String.class);verify(client).structuredImage(anyString(),task.capture(),any(),anyMap());
        assertThat(task.getValue()).contains(lesson.path("criterion").asText());
        var traits=json.valueToTree(Map.of("seed",100,"sourcePhotoSha256",StyledSpriteCodec.sha(seed()),"faceBox",List.of(0,0,1,1),
            "identityDescription","Photographed puppy","motionDescription","same puppy","rearDescription","same puppy rear","reviewNote","reviewed original photo and face crop"));
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var policy=json.valueToTree(Map.of("attempt",0,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"lessons",lessons));
        var payload=codec.character(UUID.randomUUID(),traits,seed(),policy);
        assertThat(payload.path("description").asText()).contains(lesson.path("prevention").asText(),"No hollow eye rings");
        assertThat(payload.path("description").asText().length()).isLessThanOrEqualTo(2000);
        ((tools.jackson.databind.node.ObjectNode)lesson).put("action","WALK");
        assertThatThrownBy(()->agent.review(seed(),Collections.nCopies(4,seed()),lessons)).hasMessage("QUALITY_SEED_RESPONSE_INVALID");
    }
}
