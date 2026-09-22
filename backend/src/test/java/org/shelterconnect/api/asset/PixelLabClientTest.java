package org.shelterconnect.api.asset;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PixelLabClientTest {
    HttpServer server; PixelLabClient client; JsonMapper json=JsonMapper.builder().build();
    int status=202; String response;AtomicReference<JsonNode> submitted=new AtomicReference<>();
    static AssetProperties properties() { return new AssetProperties(true,true,"test-secret","sb_secret_testing","dog-photos","dog-assets",10); }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");
            byte[] body=exchange.getRequestBody().readAllBytes();if(body.length>0)submitted.set(json.readTree(body));
            byte[] out=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,out.length);exchange.getResponseBody().write(out);exchange.close();
        });server.start();
        client=new PixelLabClient(properties(),json,AssetHttp.client(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
        response="{\"background_job_id\":\""+UUID.randomUUID()+"\"}";
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void correctPhotoAndEightMotionRequestsUseNativeSprites() {
        client.submit(AssetAction.BASE,SpriteNormalizerTest.frame(0,0));
        assertThat(submitted.get().at("/reference_images/0/size/width").asInt()).isEqualTo(64);
        for(var action:AssetAction.values())if(action!=AssetAction.BASE) {
            client.submit(action,SpriteNormalizerTest.frame(0,0));
            assertThat(submitted.get().at("/image_size/width").asInt()).isEqualTo(64);
            assertThat(submitted.get().path("action").asText().length()).isLessThanOrEqualTo(500);
            assertThat(submitted.get().path("action").asText()).contains("Same puppy identity");
            assertThat(submitted.get().path("no_background").asBoolean()).isTrue();
        }
    }
    @Test void ambiguousSubmissionIsNotMisreportedAsSafeToRetry() {
        status=503;
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=202;response="{}";
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=402;
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isFalse());
    }
    @Test void pollPreservesTheReturnedSeedPlusFrames() {
        status=200;String frame=Base64.getEncoder().encodeToString(SpriteNormalizerTest.frame(0,0));
        response=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",Collections.nCopies(16,Map.of("base64",frame)))));
        assertThat(client.poll(UUID.randomUUID()).images()).hasSize(16);
        response="{\"status\":\"failed\"}";assertThat(client.poll(UUID.randomUUID()).status()).isEqualTo("FAILED");
    }
}
