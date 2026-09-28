package org.shelterconnect.sample;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/** Local, opt-in browser adapter. Only this run's ordinary-user tokens reach the selected API. */
public final class BrowserFlowCheck extends LoginStorageCheck {
    static final String BOMI = "02200000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "02000000-0000-4000-8000-000000000001";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String localKey = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String photoId = UUID.randomUUID().toString();
    private final String photoKey = BOMI + "/browser-check-" + photoId + ".png";
    private final String bucket;
    private final boolean paidAi;
    private final Map<String, TestUser> accounts = new ConcurrentHashMap<>();
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger replyRequests = new AtomicInteger();
    private final CountDownLatch finish = new CountDownLatch(1), cleaned = new CountDownLatch(1);
    private final Object requestLock = new Object();
    private volatile String state = "PREPARING";
    private volatile int requests;
    private volatile int generatedReplies;
    private HttpServer web;
    private String localOrigin;

    BrowserFlowCheck(Map<String, String> env) {
        super(env, "b34");
        base = DeployedStorageCheck.validateOrigin(env.getOrDefault("DEPLOYMENT_CHECK_ORIGIN", ""));
        bucket = env.getOrDefault("PHOTO_STORAGE_BUCKET", "dog-photos");
        require(bucket.equals("dog-photos"), "Only the development dog-photos bucket is supported.");
        paidAi = env.getOrDefault("WEB_CHECK_PAID_AI", "false").equals("true");
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "Use the documented browser launcher.");
            var check = new BrowserFlowCheck(System.getenv());
            check.startWeb();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                check.finish.countDown();
                try { check.cleaned.await(45, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                check.web.stop(0);
            }));
            Thread.ofPlatform().start(() -> {
                try { check.run(); check.state = "CLEANED"; }
                catch (Exception failure) {
                    check.state = "FAILED";
                    System.err.println("Browser check failed. Use the private recovery record; no credentials or response bodies were printed.");
                } finally {
                    check.tokens.clear(); check.accounts.clear(); check.cleaned.countDown();
                }
            });
            // Keep the completed, read-only results available for inspection, then release this helper.
            Thread.ofPlatform().daemon().start(() -> {
                try { check.cleaned.await(); Thread.sleep(Duration.ofMinutes(30).toMillis()); }
                catch (InterruptedException ignored) { }
                check.web.stop(0);
            });
        } catch (Exception failure) {
            System.err.println("Cannot start browser check. Check the selected development target and local port.");
            System.exit(1);
        }
    }

    @Override protected void verify() throws Exception {
        try {
            accounts.put("A", createUser("a")); accounts.put("B", createUser("b"));
            preparePhoto();
            state = "READY";
            // Closing the browser does not leak fixtures indefinitely.
            finish.await(30, TimeUnit.MINUTES);
            synchronized (requestLock) {
                state = "CLEANING";
                try (var db = connection(); var q = db.prepareStatement("""
                        SELECT count(*) FROM shelter.chat_messages m JOIN shelter.chat_sessions s ON s.id=m.session_id
                        JOIN shelter.app_users u ON u.id=s.user_id
                        WHERE u.auth_subject IN (?,?) AND m.generation_response_id IS NOT NULL
                            AND m.processing_status='COMPLETED'
                        """)) {
                    q.setString(1, accounts.get("A").id()); q.setString(2, accounts.get("B").id());
                    try (var rows = q.executeQuery()) { rows.next(); generatedReplies = rows.getInt(1); }
                }
            }
        } finally {
            synchronized (requestLock) {
                state = "CLEANING";
                removePhoto();
            }
        }
    }

    void startWeb() throws Exception {
        int port = Integer.parseInt(env.getOrDefault("WEB_CHECK_PORT", "8891"));
        require(port >= 1024 && port <= 65535, "Use an unprivileged local port.");
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        localOrigin = "http://127.0.0.1:" + port;
        web.createContext("/", this::handle);
        web.setExecutor(Executors.newFixedThreadPool(4, r -> { var t = new Thread(r); t.setDaemon(true); return t; }));
        web.start();
        System.out.println("Browser verification: " + localOrigin + "/");
    }

    void stopWeb() { if (web != null) web.stop(0); }

    @Override protected String completionScope() {
        return "Browser flow ended; exact temporary users, personal records and original checkerboard photo cleaned. No role grants or schema/RLS changes.";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var h = exchange.getResponseHeaders();
            h.set("Cache-Control", "no-store"); h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer"); h.set("X-Frame-Options", "DENY");
            h.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' "
                    + env.get("SUPABASE_URL") + "; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            if (!safeLocalRequest(localOrigin, exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Origin"), exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) {
                respond(exchange, 403, Map.of("message", "이 컴퓨터의 테스트 화면에서만 사용할 수 있어요.")); return;
            }
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            if (method.equals("GET") && Set.of("/", "/index.html", "/app.js", "/style.css").contains(path)) {
                String file = path.equals("/") ? "index.html" : path.substring(1);
                h.set("Content-Type", file.endsWith(".js") ? "text/javascript; charset=utf-8"
                        : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
                bytes(exchange, 200, Files.readAllBytes(Path.of("scripts/browser-flow", file))); return;
            }
            if (method.equals("GET") && path.equals("/local/config")) {
                respond(exchange, 200, Map.of("key", localKey, "state", state, "apiOrigin", base,
                        "paidAi", paidAi, "photoId", photoId, "dogId", BOMI,
                        "requests", requests, "generatedReplies", generatedReplies)); return;
            }
            if (!localKey.equals(exchange.getRequestHeaders().getFirst("X-Local-Key"))) {
                respond(exchange, 403, Map.of("message", "화면을 다시 열어 주세요.")); return;
            }
            if (method.equals("POST") && path.equals("/local/finish")) {
                synchronized (requestLock) {
                    if (state.equals("READY") || state.equals("PREPARING")) { state = "CLEANING"; finish.countDown(); }
                }
                respond(exchange, 202, Map.of("message", "임시 계정과 자료를 정리하고 있어요.")); return;
            }
            synchronized (requestLock) {
                if (!state.equals("READY")) { respond(exchange, 409, Map.of("message", "검증 세션이 준비되지 않았거나 종료됐어요.")); return; }
                if (method.equals("POST") && path.equals("/local/login")) {
                    JsonNode input = json.readTree(boundedBody(exchange));
                    var user = accounts.get(input.path("account").asText());
                    if (user == null) { respond(exchange, 400, Map.of("message", "계정 A 또는 B를 선택해 주세요.")); return; }
                    String token = login(user); tokens.add(token);
                    respond(exchange, 200, Map.of("accessToken", token, "account", input.path("account").asText())); return;
                }
                if (path.startsWith("/proxy/")) {
                    String target = exchange.getRequestURI().toString().substring("/proxy".length());
                    if (!allowedRoute(method, target)) { respond(exchange, 403, Map.of("message", "이 검증 도구에서 허용하지 않는 요청이에요.")); return; }
                    String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                    if (bearer != null && (!bearer.startsWith("Bearer ") || !tokens.contains(bearer.substring(7)))) {
                        respond(exchange, 403, Map.of("message", "이번 검증의 임시 계정만 사용할 수 있어요.")); return;
                    }
                    if (target.endsWith("/reply") && (!paidAi || replyRequests.incrementAndGet() > 3)) {
                        respond(exchange, 409, Map.of("code", "LOCAL_AI_LIMIT", "message", "실제 AI 검증은 명시적으로 켠 세션에서 최대 3회 요청할 수 있어요.")); return;
                    }
                    byte[] body = boundedBody(exchange);
                    var req = HttpRequest.newBuilder(URI.create(base + target)).timeout(Duration.ofSeconds(75))
                            .header("Content-Type", "application/json");
                    if (bearer != null) req.header("Authorization", bearer);
                    req.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                    var response = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (var stream = response.body()) {
                        byte[] payload = stream.readNBytes(2_097_153);
                        if (payload.length > 2_097_152) throw new IOException();
                        for (String name : List.of("Retry-After", "X-Request-ID")) response.headers().firstValue(name).ifPresent(v -> h.set(name, v));
                        h.set("Content-Type", "application/json; charset=utf-8");
                        requests++; bytes(exchange, response.statusCode(), payload); return;
                    }
                }
            }
            respond(exchange, 404, Map.of("message", "없는 경로예요."));
        } catch (Exception failure) {
            // Never log request bodies, tokens, URLs containing signatures, or upstream errors.
            System.err.println("Browser helper request failed: " + failure.getClass().getSimpleName());
            try { respond(exchange, 502, Map.of("message", "로컬 연결 또는 원격 응답을 확인해 주세요. 자동으로 다시 요청하지 않았어요.")); }
            catch (IOException ignored) { }
        } finally { exchange.close(); }
    }

    static boolean safeLocalRequest(String origin, String host, String sentOrigin, String fetchSite) {
        return origin.equals("http://" + host) && (sentOrigin == null || sentOrigin.equals(origin))
                && (fetchSite == null || Set.of("same-origin", "none").contains(fetchSite));
    }

    static boolean allowedRoute(String method, String target) {
        // No arbitrary upstream URLs, redirects, management APIs, fragments or encoded traversal.
        if (!target.matches("/v1/[A-Za-z0-9/?=&._:-]+") || target.contains("..") || target.length() > 1500) return false;
        if (target.contains("?") && (!method.equals("GET")
                || !target.substring(target.indexOf('?')+1).matches("limit=[0-9]{1,2}"))) return false;
        String path = target.split("\\?", 2)[0];
        if (method.equals("GET")) return path.matches("/v1/shelters(?:/" + UUID_PATTERN + "(?:/dogs)?)?")
                || path.matches("/v1/dogs/" + UUID_PATTERN + "(?:/(?:behavior|assets|photos))?")
                || path.matches("/v1/me(?:/shelters|/chat-sessions|/adoption-notes(?:/" + UUID_PATTERN + ")?)?")
                || path.matches("/v1/chat-sessions/" + UUID_PATTERN + "(?:/messages)?");
        if (method.equals("POST")) return path.equals("/v1/me") || path.equals("/v1/dogs/" + BOMI + "/chat-sessions")
                || path.matches("/v1/chat-sessions/" + UUID_PATTERN + "/messages(?:/" + UUID_PATTERN + "/reply)?");
        return method.equals("PUT") && path.equals("/v1/me/adoption-notes/" + BOMI);
    }

    private byte[] boundedBody(HttpExchange exchange) throws IOException {
        byte[] data = exchange.getRequestBody().readNBytes(65_537);
        if (data.length > 65_536) throw new IOException("Oversized local request");
        return data;
    }
    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        bytes(exchange, status, json.writeValueAsBytes(body));
    }
    private static void bytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }

    private void preparePhoto() throws Exception {
        Files.writeString(work.resolve("browser-photo.txt"), photoId + "\n" + photoKey + "\n");
        try (var db = connection(); var q = db.prepareStatement("SELECT name FROM shelter.dogs WHERE id=?")) {
            q.setObject(1, UUID.fromString(BOMI));
            try (var row = q.executeQuery()) { require(row.next() && row.getString(1).equals("봄이"), "Fictional Bomi fixture required."); }
        }
        var response = storage("POST", "/object/" + bucket + "/" + photoKey, PhotoStorageConnectionCheck.fixture(), "image/png");
        require(response == 200, "Cannot prepare the original checkerboard test image.");
        try (var db = connection(); var q = db.prepareStatement("""
                INSERT INTO shelter.dog_photos (id,dog_id,storage_bucket,storage_key,sort_order,caption,source_note,
                    rights_status,rights_note,rights_confirmed_by,rights_confirmed_at)
                VALUES (?,?,?,?,(SELECT COALESCE(MAX(sort_order),-1)+1 FROM shelter.dog_photos WHERE dog_id=?),?,?,
                    'GRANTED','Original checkerboard drawn by this verification tool',?,now())
                """)) {
            q.setObject(1,UUID.fromString(photoId)); q.setObject(2,UUID.fromString(BOMI)); q.setString(3,bucket); q.setString(4,photoKey);
            q.setObject(5,UUID.fromString(BOMI)); q.setString(6,"직접 만든 저장소 검증 이미지 · 실제 강아지 사진 아님");
            q.setString(7,marker); q.setObject(8,UUID.fromString(OPERATOR)); q.executeUpdate();
        }
    }
    private void removePhoto() throws Exception {
        try (var db = connection(); var q = db.prepareStatement("DELETE FROM shelter.dog_photos WHERE id=? AND dog_id=? AND source_note=? AND storage_key=?")) {
            q.setObject(1,UUID.fromString(photoId)); q.setObject(2,UUID.fromString(BOMI)); q.setString(3,marker); q.setString(4,photoKey); q.executeUpdate();
        }
        int status = storage("DELETE", "/object/" + bucket, json.writeValueAsBytes(Map.of("prefixes", List.of(photoKey))), "application/json");
        require(status == 200, "Test Storage cleanup incomplete; use the private browser-photo record.");
        System.out.println("PASS exact temporary photo row and checkerboard object cleanup.");
    }
    private int storage(String method, String path, byte[] body, String mime) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(env.get("SUPABASE_URL") + "/storage/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("apikey",env.get("SUPABASE_SECRET_KEY"))
                .header("Content-Type",mime).header("x-upsert","false")
                .method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
