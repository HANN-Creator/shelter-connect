package org.shelterconnect.api.asset;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class StyledQualityTimeoutTest {
    @Test void realV33FailureRequiresSavedPayloadNotAQualityVerdict()throws Exception {
        var json=JsonMapper.builder().build();var evidence=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/quality-timeout-v33/failure.json")));
        assertThat(evidence.path("failureCode").asText()).isEqualTo("QUALITY_AI_TIMEOUT");
        assertThat(evidence.at("/failedStep/label").asText()).isEqualTo("idle-west");
        assertThat(evidence.path("seedApprovalActor").asText()).isEqualTo("SYSTEM");
        assertThat(evidence.at("/failedStep/result").isNull()).isTrue();
        assertThat(evidence.path("providerCheckpointPersisted").asBoolean()).isTrue();
        var checkpoint=json.createObjectNode();checkpoint.set("quality",evidence.at("/failedStep/qualityReport"));
        assertThat(StyledQualityTimeout.started(checkpoint)).isFalse();
        checkpoint.putObject("payload").put("syntheticTestPayload",true);
        assertThat(StyledQualityTimeout.started(checkpoint)).isTrue();
        checkpoint.set("quality",json.createObjectNode().put("passed",false));
        assertThat(StyledQualityTimeout.started(checkpoint)).isFalse();
    }
}
