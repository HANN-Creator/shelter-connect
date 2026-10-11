package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in public-photo analysis using the production provider/converter, never image generation. */
class StyledPhotoInputLiveTest {
    @Test void threeNewPhotographsProduceValidPersistableNativeInputs()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("STYLED_PHOTO_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var root=Path.of(System.getenv("STYLED_PHOTO_LIVE_OUTPUT"));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),System.getenv("OPENAI_MODEL"),60);
        var provider=new OpenAiPhotoAppearance(new OpenAiResponsesClient(ai,json),ai);
        for(int i=1;i<=3;i++) {
            var dir=root.resolve("source-"+i);byte[] photo=Files.readAllBytes(dir.resolve("normalized.png"));
            var intent=dir.resolve("analysis-intent.json");var receipt=dir.resolve("analysis.json");
            var binding=json.valueToTree(Map.of("photoSha256",StyledSpriteCodec.sha(photo),"version",StyledPhotoInput.VERSION,"model",provider.model()));
            tools.jackson.databind.JsonNode analysis;
            if(Files.exists(receipt)) {
                assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);
                analysis=json.readTree(Files.readAllBytes(receipt));
            } else {
                assertThat(Files.exists(intent)).as("Unknown paid analysis outcome; do not replay blindly").isFalse();
                Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
                analysis=provider.analyzeStyled(photo);Files.write(receipt,json.writeValueAsBytes(analysis),StandardOpenOption.CREATE_NEW);
            }
            var traits=StyledPhotoInput.traits(photo,analysis,json);
            Files.write(dir.resolve("traits.json"),json.writeValueAsBytes(traits));
            Files.write(dir.resolve("face.png"),PhotoAppearance.prepare(photo,analysis).head());
            Files.write(dir.resolve("analysis-verification.json"),json.writeValueAsBytes(Map.of("valid",true,"photoSha256",StyledSpriteCodec.sha(photo),"model",provider.model(),"traitsSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(traits)),"version",StyledPhotoInput.VERSION)));
        }
    }
}
