package org.shelterconnect.api.asset;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SupabaseAssetStorageTest {
    HttpServer server;SupabaseAssetStorage storage;JsonMapper json=JsonMapper.builder().build();
    boolean publicBucket=false, foreignLink=false;AtomicInteger objects=new AtomicInteger();String sourcePath;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{
            assertThat(ex.getRequestHeaders().getFirst("apikey")).isEqualTo("sb_secret_testing");
            assertThat(ex.getRequestHeaders().getFirst("Authorization")).isNull();
            String path=ex.getRequestURI().getPath(),response;
            if(path.startsWith("/bucket/")) response=json.writeValueAsString(Map.of("id",path.substring(8),"public",publicBucket));
            else if(path.startsWith("/object/sign/")) {
                var keys=json.readTree(ex.getRequestBody().readAllBytes()).get("paths");var links=new ArrayList<Map<String,String>>();
                for(var k:keys) links.add(Map.of("path",k.asText(),"signedURL",foreignLink?"https://other.example.invalid/object":"/object/sign/dog-assets/"+k.asText()+"?token=abc.def"));
                response=json.writeValueAsString(links);
            } else { objects.incrementAndGet();sourcePath=path;response="{}"; }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();storage=new SupabaseAssetStorage(PixelLabClientTest.properties(),json,AssetHttp.client(),"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void onlyPrivateBucketsAndOwnedPathsCanBeDownloaded() {
        var dog=UUID.randomUUID();storage.photo(dog,"dog-photos",dog+"/source.png");
        assertThat(sourcePath).isEqualTo("/object/authenticated/dog-photos/"+dog+"/source.png");
        assertThatThrownBy(()->storage.photo(dog,"dog-photos",UUID.randomUUID()+"/source.png")).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->storage.photo(dog,"other",dog+"/source.png")).isInstanceOf(AssetException.class);
        for(String key:List.of(dog+"/../secret",dog+"/%2e%2e/secret",dog+"//secret",dog+"/https://external"))
            assertThatThrownBy(()->storage.photo(dog,"dog-photos",key)).isInstanceOf(AssetException.class);
        publicBucket=true;assertThatThrownBy(()->storage.photo(dog,"dog-photos",dog+"/source.png")).isInstanceOf(AssetException.class);
        assertThat(objects).hasValue(1);
    }
    @Test void signedUrlsMustRemainOnTheConfiguredProjectAndRequestedObject() {
        assertThat(storage.sign(List.of("dog/job/base.png"))).containsKey("dog/job/base.png");
        foreignLink=true;assertThatThrownBy(()->storage.sign(List.of("dog/job/base.png"))).isInstanceOf(AssetException.class);
        publicBucket=true;assertThatThrownBy(()->storage.put("dog/job/base.png",new byte[]{1})).isInstanceOf(AssetException.class);
        assertThat(objects).hasValue(0);
    }
}
