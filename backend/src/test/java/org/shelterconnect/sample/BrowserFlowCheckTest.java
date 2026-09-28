package org.shelterconnect.sample;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BrowserFlowCheckTest {
    private final String session = "10000000-0000-4000-8000-000000000001";

    @Test void proxyDoesNotBecomeAnArbitraryDestinationOrManagementClient() {
        for (String path : List.of("https://evil.test/v1/me", "//evil.test/v1/me", "/v1/../me", "/v1/%2e%2e/me",
                "/v1/me#fragment", "/v1/me?next=https://evil.test", "/v1/shelter-admin/dogs", "/v1/operators/shelters"))
            assertThat(BrowserFlowCheck.allowedRoute("GET", path)).as(path).isFalse();
        assertThat(BrowserFlowCheck.allowedRoute("POST", "/v1/dogs/"+session+"/chat-sessions")).isFalse();
        assertThat(BrowserFlowCheck.allowedRoute("PUT", "/v1/me/adoption-notes/"+session)).isFalse();
        assertThat(BrowserFlowCheck.allowedRoute("DELETE", "/v1/me")).isFalse();
        // A query suffix must not bypass the explicit AI opt-in and request cap.
        assertThat(BrowserFlowCheck.allowedRoute("POST", "/v1/chat-sessions/"+session+"/messages/"+session+"/reply?limit=1")).isFalse();
        assertThat(BrowserFlowCheck.allowedRoute("GET", "/v1/shelters")).isTrue();
        assertThat(BrowserFlowCheck.allowedRoute("POST", "/v1/dogs/"+BrowserFlowCheck.BOMI+"/chat-sessions")).isTrue();
        assertThat(BrowserFlowCheck.allowedRoute("GET", "/v1/chat-sessions/"+session+"/messages?limit=50")).isTrue();
    }

    @Test void localHelperRejectsCrossSiteRequestsAndDnsRebinding() {
        String local="http://127.0.0.1:8891";
        assertThat(BrowserFlowCheck.safeLocalRequest(local,"127.0.0.1:8891",null,"none")).isTrue();
        assertThat(BrowserFlowCheck.safeLocalRequest(local,"127.0.0.1:8891",local,"same-origin")).isTrue();
        assertThat(BrowserFlowCheck.safeLocalRequest(local,"evil.test:8891",null,null)).isFalse();
        assertThat(BrowserFlowCheck.safeLocalRequest(local,"127.0.0.1:8891","https://evil.test",null)).isFalse();
        assertThat(BrowserFlowCheck.safeLocalRequest(local,"127.0.0.1:8891",null,"cross-site")).isFalse();
    }

    @Test void servedBrowserConfigurationDoesNotExposeServerKeysOrEnableCrossSiteActions() throws Exception {
        int port;try(var free=new ServerSocket(0)){port=free.getLocalPort();}
        var env=new HashMap<>(Map.of("AUTH_CHECK_PROJECT_REF","a".repeat(20),
            "SUPABASE_URL","https://"+"a".repeat(20)+".supabase.co", "DB_USERNAME","postgres."+"a".repeat(20),
            "DB_URL","jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/ca.crt",
            "DB_PASSWORD","private-test-password", "SUPABASE_SECRET_KEY","sb_secret_private_test",
            "DEPLOYMENT_CHECK_ORIGIN","https://shelter-connect-dev.onrender.com", "WEB_CHECK_PORT",Integer.toString(port)));
        var app=new BrowserFlowCheck(env);app.startWeb();
        var client=HttpClient.newHttpClient();String origin="http://127.0.0.1:"+port;
        try {
            var config=client.send(HttpRequest.newBuilder(URI.create(origin+"/local/config")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(config.statusCode()).isEqualTo(200);
            assertThat(config.body()).doesNotContain("private-test-password","sb_secret_private_test","DB_URL");
            assertThat(config.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(config.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            String key=JsonMapper.builder().build().readTree(config.body()).path("key").asText();
            for (String path : List.of("/local/login", "/local/finish", "/proxy/v1/me")) {
                var denied=client.send(HttpRequest.newBuilder(URI.create(origin+path)).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(403);
            }
            var cross=client.send(HttpRequest.newBuilder(URI.create(origin+"/local/finish")).header("Origin","https://evil.test")
                .header("X-Local-Key",key).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(cross.statusCode()).isEqualTo(403);
            var notReady=client.send(HttpRequest.newBuilder(URI.create(origin+"/local/login")).header("X-Local-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"account\":\"A\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(notReady.statusCode()).isEqualTo(409);
            var page=client.send(HttpRequest.newBuilder(URI.create(origin+"/")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);assertThat(page.body()).contains("연결 테스트");
            assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors 'none'");
        } finally {app.stopWeb();}
    }
}
