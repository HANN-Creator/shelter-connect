package org.shelterconnect.api.asset;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class StyledPixelLabClientTest {
    HttpServer server;StyledPixelLabClient client;JsonMapper json=JsonMapper.builder().build();byte[] png;
    String body="{}",requestPath;int status=200;byte[] archive;
    @BeforeEach void setup() throws Exception {
        png=new StyledSpriteCodecTest().image(32);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{requestPath=ex.getRequestURI().getPath();assertThat(ex.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");ex.getRequestBody().readAllBytes();byte[] bytes=requestPath.endsWith("/zip")?archive:body.getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);ex.close();});server.start();
        client=new StyledPixelLabClient(PixelLabClientTest.properties(),json,AssetHttp.client(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
    }
    @AfterEach void stop() {server.stop(0);}
    @Test void correctEndpointsAndUncertainAcknowledgementArePreserved() {
        UUID id=UUID.randomUUID();body=json.writeValueAsString(Map.of("background_job_id",id));
        assertThat(client.submit(true,json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/create-character-pro");
        assertThat(client.submit(false,json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/animate-pixminimax");
        assertThat(client.editAnimation(json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/edit-animation-v2");
        assertThat(client.editSeedEyes(json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/inpaint-v3");
        body="{}";assertThatThrownBy(()->client.submit(true,json.readTree("{}"))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=429;assertThatThrownBy(()->client.submit(true,json.readTree("{}"))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isFalse());
    }
    @Test void archiveSelectsFourExactNativeRotationsWithoutExtractingOtherFiles() throws Exception {
        UUID character=UUID.randomUUID();body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("character_id",character)));
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)) {
            for(String d:StyledSpriteCodec.DIRECTIONS) {zip.putNextEntry(new ZipEntry("pet/rotations/"+d+".png"));zip.write(png);zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("../../other.txt"));zip.write(new byte[50]);zip.closeEntry();
        }
        archive=out.toByteArray();var result=client.poll(UUID.randomUUID(),true);
        assertThat(result.path("directions").size()).isEqualTo(4);
        assertThat(StyledPixelLabClient.decode(result.at("/directions/east").asText())).isEqualTo(png);
        assertThat(requestPath).isEqualTo("/characters/"+character+"/zip");
    }
    @Test void nineFramesAreRequiredAndProviderUrlsAreNeverFollowed() {
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",Collections.nCopies(9,Map.of("base64",Base64.getEncoder().encodeToString(png))))));
        assertThat(client.poll(UUID.randomUUID(),false).path("frames").size()).isEqualTo(9);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",List.of(Map.of("url","http://127.0.0.1/internal")))));
        assertThatThrownBy(()->client.poll(UUID.randomUUID(),false)).isInstanceOf(AssetException.class);
    }
    @Test void eyeEditRequiresAnExactNativeFourViewStripAndNeverFollowsUrls() {
        byte[] raw=StyledSeedEyeRepair.png(StyledSeedEyeRepair.strip(Collections.nCopies(4,png)));
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",StyledSeedEyeRepair.encoded(raw))));
        assertThat(Base64.getDecoder().decode(client.pollSeedEyes(UUID.randomUUID()).path("eyeSheet").asText())).isEqualTo(raw);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",StyledSeedEyeRepair.encoded(png))));
        assertThatThrownBy(()->client.pollSeedEyes(UUID.randomUUID())).isInstanceOf(AssetException.class);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",Map.of("url","http://127.0.0.1/internal"))));
        assertThatThrownBy(()->client.pollSeedEyes(UUID.randomUUID())).isInstanceOf(AssetException.class);
        assertThat(requestPath).startsWith("/background-jobs/");
    }
}
