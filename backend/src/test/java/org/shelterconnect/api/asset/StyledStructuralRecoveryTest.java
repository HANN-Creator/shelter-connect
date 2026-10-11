package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class StyledStructuralRecoveryTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void opaqueProviderBytesStayIntactAndNeverPassNativeValidation()throws Exception {
        var images=new LinkedHashMap<String,byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)images.put(d,Files.readAllBytes(Path.of("scripts/fixtures/opaque-character-v38/"+d+".png")));
        var report=StyledStructuralRecovery.inspect(images,json);
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("visualReviewPerformed").asBoolean()).isFalse();
        assertThat(report.path("failedDirections").size()).isEqualTo(4);
        for(String d:StyledSpriteCodec.DIRECTIONS){assertThat(report.at("/alphaMeasurements/"+d+"/transparentPixels").asInt()).isZero();
            assertThatThrownBy(()->StyledSpriteCodec.nativeFrame(images.get(d))).hasMessage("STYLED_ALPHA_INVALID");}
        var white=new StyledSpriteCodecTest().image(32);var im=StyledSpriteCodec.nativeFrame(white);im.setRGB(15,15,0xffffffff);
        byte[] valid=StyledSpriteCodec.png(im);for(String d:StyledSpriteCodec.DIRECTIONS)images.put(d,valid);
        assertThat(StyledStructuralRecovery.inspect(images,json)).isNull();assertThat(StyledSpriteCodec.nativeFrame(valid).getRGB(15,15)).isEqualTo(0xffffffff);
    }
    @Test void actualIdleFailureCanRestartButUncertaintyAndStalePolicyCannot()throws Exception {
        var root=Path.of("scripts/fixtures/idle-restart-v38");
        var report=(tools.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(root.resolve("idle-south-review.json")));
        byte[] sheet=Files.readAllBytes(root.resolve("idle-south.png"));
        assertThat(StyledSpriteCodec.sha(sheet)).isEqualTo(report.path("inputSha256").asText());
        assertThat(StyledSpriteCodec.frames(sheet).stream().map(StyledSpriteCodec::sha).toList())
            .isEqualTo(report.path("reviewedFrameHashes").valueStream().map(n->n.asText()).toList());
        assertThat(StyledIdleRestart.confirmed(report)).isFalse();
        report.put("rulesSha256",StyledSpriteCodec.qualityRulesSha()); // Routing replay only, not a fresh visual verdict.
        assertThat(StyledIdleRestart.confirmed(report)).isTrue();
        var uncertain=(tools.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(root.resolve("idle-west-review.json")));
        uncertain.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());assertThat(StyledIdleRestart.confirmed(uncertain)).isFalse();
        report.putArray("edgeFrames").add(8);assertThat(StyledIdleRestart.confirmed(report)).isFalse();
    }
}
