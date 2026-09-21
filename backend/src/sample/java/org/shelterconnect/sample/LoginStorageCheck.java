package org.shelterconnect.sample;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.shelterconnect.api.auth.SupabaseProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit development check only. Creates ordinary test users, then removes only its own data. */
public final class LoginStorageCheck {
    private static final String BOMI = "02200000-0000-4000-8000-000000000001";
    private static final String DUBU = "02200000-0000-4000-8000-000000000002";
    private static final String HAERI = "02200000-0000-4000-8000-000000000005";
    private static final String SHELTER = "02100000-0000-4000-8000-000000000001";
    private static final List<String> TABLES = List.of("app_users", "shelters", "shelter_memberships", "dogs",
            "dog_observations", "dog_photos", "dog_behavior_profiles", "dog_behavior_evidence",
            "chat_sessions", "chat_messages", "chat_message_observations", "adoption_notes");
    private final Map<String, String> env;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String project, key, marker = "b13-" + UUID.randomUUID();
    private final List<TestUser> users = new ArrayList<>();
    private Path work;
    private Process server;
    private String base;
    private int checks;
    private boolean creationInFlight;
    private record TestUser(String id, String email, String password) {}
    private static final class Failure extends RuntimeException {
        Failure(String message) { super(message); }
    }

    LoginStorageCheck(Map<String, String> env) {
        validateTarget(env);
        this.env = env;
        project = env.get("SUPABASE_URL");
        key = env.get("SUPABASE_SECRET_KEY");
    }

    static void validateTarget(Map<String, String> env) {
        String ref = env.getOrDefault("AUTH_CHECK_PROJECT_REF", "");
        require(ref.matches("[a-z0-9]{20}") && ("https://" + ref + ".supabase.co").equals(env.get("SUPABASE_URL")),
                "Explicit development project ref must match SUPABASE_URL.");
        require(("postgres." + ref).equals(env.get("DB_USERNAME")), "Database user must match the Auth project.");
        require(env.getOrDefault("SUPABASE_SECRET_KEY", "").matches("sb_secret_[A-Za-z0-9_-]+"), "A server key is required.");
        require(env.getOrDefault("DB_URL", "").matches(
                "jdbc:postgresql://aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com:5432/postgres\\?sslmode=require"),
                "Use the development session pooler with SSL.");
        require(!env.getOrDefault("DB_PASSWORD", "").isBlank(), "Database password is required.");
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "Use the documented local launcher.");
            new LoginStorageCheck(System.getenv()).run();
        } catch (Failure failure) {
            System.err.println(failure.getMessage()); // Only constant diagnostics/statuses; no response bodies.
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Login/storage check failed. Inspect the private local check directory; secrets were not printed.");
            System.exit(1);
        }
    }

    private void run() throws Exception {
        require(Files.isRegularFile(Path.of("build/libs/shelter-connect-api.jar")), "Build the API JAR first.");
        work = Files.createTempDirectory(Path.of("build"), "login-check-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        System.out.println("Private recovery record: " + work.resolve("created-users.txt"));
        Map<String, Long> before = counts();
        try {
            startServer();
            TestUser a = createUser("a"), b = createUser("b");
            String tokenA = login(a), tokenB = login(b);
            String aId = register(tokenA), bId = register(tokenB);
            require(!aId.equals(bId), "Two logins must produce separate app users.");
            System.out.println("PASS real password login, user registration/idempotency, ordinary-user permissions.");
            var saved = exercise(tokenA, tokenB);
            stopServer();
            startServer();
            tokenA = login(a); tokenB = login(b);
            equal(api("GET", "/v1/me", tokenA, null, 200).path("data").path("id").asText(), aId, "Account after restart");
            equal(api("GET", "/v1/me", tokenB, null, 200).path("data").path("id").asText(), bId, "Second account after restart");
            equal(api("GET", "/v1/chat-sessions/" + saved.get("session"), tokenA, null, 200).path("data").path("id").asText(),
                    saved.get("session"), "Session persisted after restart");
            var messages = api("GET", "/v1/chat-sessions/" + saved.get("session") + "/messages", tokenA, null, 200).path("data");
            require(messages.size() == 2 && containsId(messages, saved.get("message")), "Messages did not persist exactly once.");
            var note = api("GET", "/v1/me/adoption-notes/" + BOMI, tokenA, null, 200).path("data");
            equal(note.path("id").asText(), saved.get("note"), "Note identity after restart");
            equal(note.path("questions").asText(), marker + " 수정한 질문", "Note content after restart");
            equal(note.path("carePlan").asText(), "테스트 돌봄 계획", "Care plan after restart");
            require(note.path("checklist").path("householdDiscussed").asBoolean(), "Checklist not persisted.");
            equal(note.path("updatedAt").asText(), saved.get("noteVersion"), "Note version after restart");
            error("GET", "/v1/chat-sessions/" + saved.get("session"), tokenB, null, 404, "CHAT_NOT_FOUND");
            equal(api("GET", "/v1/me/adoption-notes/" + BOMI, tokenB, null, 200).path("data").path("questions").asText(),
                    marker + " B의 질문", "Second user's own note");
            System.out.println("PASS server restart + fresh logins: same accounts, messages, notes, timestamps and ownership.");
        } finally {
            stopServer();
            boolean clean = true;
            for (TestUser user : users) {
                try { cleanUser(user); }
                catch (Exception failure) { clean = false; }
            }
            require(!creationInFlight, "Auth creation result was interrupted; check this run's private pending identity before marking cleanup complete.");
            require(clean, "Test cleanup incomplete; use this run's private created-users.txt. Never delete by a broad email pattern.");
            equal(counts(), before, "Original app table counts after cleanup");
            Files.writeString(work.resolve("cleanup-complete.txt"), "Only this run's users and records were removed. Original app row counts restored.\n");
            System.out.println("PASS temporary Auth users and owned app rows removed; all 12 original app table counts restored.");
        }
        System.out.println("PASS " + checks + " HTTP expectations. No AI calls, photos, role grants, schema/RLS changes or deployment.");
    }

    private TestUser createUser(String label) throws Exception {
        String email = marker + "-" + label + "@example.invalid";
        String password = UUID.randomUUID() + "aA9!" + UUID.randomUUID().toString().substring(0, 16);
        // Record the generated identity before the request so an interrupted response can be recovered manually.
        Files.writeString(work.resolve("created-users.txt"), "pending " + email + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        creationInFlight = true;
        var result = auth("POST", "/admin/users", Map.of("email", email, "password", password,
                "email_confirm", true, "app_metadata", Map.of("verification_run", marker)), 200, 201);
        String id = result.path("id").asText();
        UUID.fromString(id);
        TestUser user = new TestUser(id, email, password);
        users.add(user);
        creationInFlight = false;
        Files.writeString(work.resolve("created-users.txt"), "created " + id + " " + email + "\n", StandardOpenOption.APPEND);
        return user;
    }

    private String login(TestUser user) throws Exception {
        var result = auth("POST", "/token?grant_type=password", Map.of("email", user.email(), "password", user.password()), 200);
        equal(result.path("user").path("id").asText(), user.id(), "Supabase password login identity");
        String token = result.path("access_token").asText();
        require(token.split("\\.").length == 3, "Supabase did not return a user JWT.");
        var header = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        equal(header.path("alg").asText(), "ES256", "Actual Supabase signing algorithm");
        // This is only a diagnostic. The real server verifies signature, issuer, audience and user claims.
        return token;
    }

    private String register(String token) throws Exception {
        error("GET", "/v1/me", token, null, 403, "ACCOUNT_NOT_REGISTERED");
        var me = api("POST", "/v1/me", token, null, 200).path("data");
        equal(me.path("role").asText(), "USER", "Default app role");
        equal(api("POST", "/v1/me", token, null, 200).path("data"), me, "Idempotent registration");
        equal(api("GET", "/v1/me", token, null, 200).path("data"), me, "Profile read");
        require(api("GET", "/v1/me/shelters", token, null, 200).path("data").isEmpty(), "Test user received shelter membership.");
        api("GET", "/v1/shelter-admin/shelters/" + SHELTER + "/access", token, null, 403);
        return me.path("id").asText();
    }

    private Map<String, String> exercise(String a, String b) throws Exception {
        api("GET", "/v1/me", null, null, 401);
        api("GET", "/v1/me", "invalid", null, 401);
        api("GET", "/v1/me", key, null, 401);
        error("POST", "/v1/dogs/" + HAERI + "/chat-sessions", a, null, 404, "DOG_NOT_FOUND");
        String session = api("POST", "/v1/dogs/" + BOMI + "/chat-sessions", a, null, 201).path("data").path("id").asText();
        equal(api("POST", "/v1/dogs/" + BOMI + "/chat-sessions", a, null, 200).path("data").path("id").asText(), session, "Open session reuse");
        api("POST", "/v1/dogs/" + DUBU + "/chat-sessions", a, null, 201);
        String path = "/v1/chat-sessions/" + session + "/messages";
        var messageInput = Map.of("clientMessageId", marker + "-1", "text", marker + " 산책은 좋아해?");
        var message = api("POST", path, a, messageInput, 201).path("data");
        equal(message.path("role").asText(), "USER", "Stored message role");
        equal(message.path("processingStatus").asText(), "PENDING", "Stored message status");
        equal(api("POST", path, a, messageInput, 200).path("data"), message, "Message retry result");
        error("POST", path, a, Map.of("clientMessageId", marker + "-1", "text", marker + " 다른 내용"), 409, "MESSAGE_ID_CONFLICT");
        api("POST", path, a, Map.of("clientMessageId", marker + "-2", "text", marker + " 좋아하는 놀이는?"), 201);
        var messages = api("GET", path, a, null, 200).path("data");
        require(messages.size() == 2 && containsId(messages, message.path("id").asText()), "Unexpected saved messages.");
        for (String privatePath : List.of("/v1/chat-sessions/" + session, path)) error("GET", privatePath, b, null, 404, "CHAT_NOT_FOUND");
        error("POST", path, b, messageInput, 404, "CHAT_NOT_FOUND");
        require(api("GET", "/v1/me/chat-sessions", b, null, 200).path("data").isEmpty(), "Another user's session leaked.");
        String chatCursor = api("GET", "/v1/me/chat-sessions?limit=1", a, null, 200).path("nextCursor").asText();
        require(!chatCursor.isBlank() && !chatCursor.equals("null"), "Missing chat pagination cursor.");
        error("GET", "/v1/me/chat-sessions?limit=1&cursor=" + encode(chatCursor), b, null, 400, "INVALID_CURSOR");
        String bSession = api("POST", "/v1/dogs/" + BOMI + "/chat-sessions", b, null, 201).path("data").path("id").asText();
        require(!session.equals(bSession), "Dog chat shared between users.");
        System.out.println("PASS chat reuse, message persistence/idempotency, conflicting retry, private-dog and cross-user/cursor access denial.");

        String notePath = "/v1/me/adoption-notes/" + BOMI;
        error("GET", notePath, a, null, 404, "NOTE_NOT_FOUND");
        var note = api("PUT", notePath, a, noteInput(marker + " 처음 질문", null), 201).path("data");
        equal(api("GET", notePath, a, null, 200).path("data"), note, "Created note read");
        error("GET", notePath, b, null, 404, "NOTE_NOT_FOUND");
        require(api("GET", "/v1/me/adoption-notes", b, null, 200).path("data").isEmpty(), "Another user's notes leaked.");
        String version = note.path("updatedAt").asText();
        var updated = api("PUT", notePath, a, noteInput(marker + " 수정한 질문", version), 200).path("data");
        equal(updated.path("id").asText(), note.path("id").asText(), "Note update identity");
        require(!version.equals(updated.path("updatedAt").asText()), "Note update version did not advance.");
        error("PUT", notePath, a, noteInput(marker + " 오래된 요청", version), 409, "NOTE_VERSION_CONFLICT");
        error("PUT", notePath, a, noteInput(marker + " 중복 생성", null), 409, "NOTE_VERSION_CONFLICT");
        equal(api("PUT", notePath, a, noteInput(marker + " 수정한 질문", updated.path("updatedAt").asText()), 200).path("data"),
                updated, "No-op note update");
        api("PUT", "/v1/me/adoption-notes/" + DUBU, a, noteInput(marker + " 두부 질문", null), 201);
        var notes = api("GET", "/v1/me/adoption-notes", a, null, 200).path("data");
        require(notes.size() == 2 && containsId(notes, note.path("id").asText()), "Own note list is incomplete.");
        String noteCursor = api("GET", "/v1/me/adoption-notes?limit=1", a, null, 200).path("nextCursor").asText();
        require(!noteCursor.isBlank() && !noteCursor.equals("null"), "Missing note cursor.");
        error("GET", "/v1/me/adoption-notes?limit=1&cursor=" + encode(noteCursor), b, null, 400, "INVALID_CURSOR");
        var bNote = api("PUT", notePath, b, noteInput(marker + " B의 질문", null), 201).path("data");
        require(!bNote.path("id").equals(note.path("id")), "Dog note shared between users.");
        equal(api("GET", notePath, a, null, 200).path("data"), updated, "Other user's save must not alter this note");
        System.out.println("PASS note create/read/update/list, version conflicts, no-op saves and separate notes for the same dog.");
        return Map.of("session", session, "message", message.path("id").asText(), "note", note.path("id").asText(),
                "noteVersion", updated.path("updatedAt").asText());
    }

    private static Map<String, Object> noteInput(String questions, String version) {
        var input = new HashMap<String, Object>();
        input.put("questions", questions); input.put("carePlan", "테스트 돌봄 계획");
        input.put("checklist", Map.of("householdDiscussed", true, "budgetPlanned", false));
        input.put("expectedUpdatedAt", version);
        return input;
    }

    private JsonNode auth(String method, String path, Object body, int... statuses) throws Exception {
        return request(method, project + "/auth/v1" + path, body, Map.of("apikey", key), false, statuses);
    }

    private JsonNode api(String method, String path, String token, Object body, int status) throws Exception {
        return request(method, base + path, body, token == null ? Map.of() : Map.of("Authorization", "Bearer " + token), true, status);
    }

    private void error(String method, String path, String token, Object body, int status, String code) throws Exception {
        equal(api(method, path, token, body, status).path("code").asText(), code, "Expected API error code");
    }

    private JsonNode request(String method, String url, Object body, Map<String, String> headers, boolean app, int... statuses) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        String authCode = "";
        if (!app && response.statusCode() >= 400) {
            String candidate = json.readTree(response.body()).path("error_code").asText();
            if (candidate.matches("[a-z_]{1,64}")) authCode = " (" + candidate + ")";
        }
        require(Arrays.stream(statuses).anyMatch(s -> s == response.statusCode()),
                (app ? "API" : "Auth") + " HTTP mismatch: expected " + Arrays.toString(statuses) + ", received " + response.statusCode() + " at check " + (checks + 1) + authCode);
        checks++;
        var result = response.body().length == 0 ? json.nullNode() : json.readTree(response.body());
        if (app) {
            equal(response.headers().firstValue("cache-control").orElse(""), "no-store", "Private response cache control");
            String requestId = response.headers().firstValue("x-request-id").orElse("");
            require(!requestId.isBlank(), "Missing request ID.");
            if (response.statusCode() >= 400) equal(result.path("requestId").asText(), requestId, "Error request ID");
        }
        return result;
    }

    private void startServer() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "build/libs/shelter-connect-api.jar", "--spring.config.location=classpath:/application.properties",
                "--spring.flyway.enabled=false", "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
                "--server.address=127.0.0.1", "--server.port=" + port, "--app.ai.enabled=false", "--app.photos.enabled=false");
        process.environment().clear();
        for (String name : List.of("PATH", "JAVA_HOME", "DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_POOL_SIZE", "SUPABASE_URL"))
            if (env.containsKey(name)) process.environment().put(name, env.get(name));
        process.environment().put("DB_MIGRATE", "false");
        server = process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("server.log").toFile())).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (server.isAlive() && System.nanoTime() < deadline) {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && json.readTree(response.body()).path("status").asText().equals("UP")) return;
            } catch (java.io.IOException ignored) { }
            Thread.sleep(300);
        }
        throw new Failure("Local API did not become ready; inspect the private server.log.");
    }

    private void stopServer() throws InterruptedException {
        if (server == null) return;
        server.destroy();
        if (!server.waitFor(15, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(10, TimeUnit.SECONDS); }
        require(!server.isAlive(), "Own test server could not be stopped.");
        server = null;
    }

    private Connection connection() throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", env.get("DB_USERNAME")); properties.setProperty("password", env.get("DB_PASSWORD"));
        properties.setProperty("connectTimeout", "10"); properties.setProperty("socketTimeout", "20");
        return DriverManager.getConnection(env.get("DB_URL"), properties);
    }

    private Map<String, Long> counts() throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (var db = connection(); var statement = db.createStatement()) {
            db.setReadOnly(true);
            for (String table : TABLES) try (var rows = statement.executeQuery("SELECT count(*) FROM shelter." + table)) {
                rows.next(); counts.put(table, rows.getLong(1));
            }
        }
        return counts;
    }

    private void cleanUser(TestUser user) throws Exception {
        // Never search/delete by email pattern. Check the exact newly-created UUID and its per-run marker first.
        var owned = auth("GET", "/admin/users/" + user.id(), null, 200);
        equal(owned.path("email").asText(), user.email(), "Cleanup Auth identity");
        equal(owned.path("app_metadata").path("verification_run").asText(), marker, "Cleanup run ownership");
        try (var db = connection()) {
            db.setAutoCommit(false);
            try {
                var subjects = db.prepareStatement("SELECT id, role FROM shelter.app_users WHERE auth_provider=? AND auth_subject=? FOR UPDATE");
                subjects.setString(1, new SupabaseProperties(project).providerKey()); subjects.setString(2, user.id());
                try (subjects; var rows = subjects.executeQuery()) {
                    if (rows.next()) {
                        UUID id = rows.getObject(1, UUID.class);
                        equal(rows.getString(2), "USER", "Cleanup ordinary user");
                        require(!rows.next(), "Duplicate app account.");
                        try (var members = db.prepareStatement("SELECT count(*) FROM shelter.shelter_memberships WHERE user_id=?")) {
                            members.setObject(1, id);
                            try (var result = members.executeQuery()) { result.next(); require(result.getInt(1) == 0, "Cleanup refuses a shelter member."); }
                        }
                        // Restrictive FKs reject any unexpected role/evidence data rather than cascading into it.
                        execute(db, "DELETE FROM shelter.chat_messages WHERE session_id IN (SELECT id FROM shelter.chat_sessions WHERE user_id=?)", id);
                        execute(db, "DELETE FROM shelter.chat_sessions WHERE user_id=?", id);
                        execute(db, "DELETE FROM shelter.adoption_notes WHERE user_id=?", id);
                        equal(execute(db, "DELETE FROM shelter.app_users WHERE id=?", id), 1, "Cleanup account count");
                    }
                }
                db.commit();
            } catch (Exception failure) { db.rollback(); throw failure; }
        }
        auth("DELETE", "/admin/users/" + user.id(), null, 200, 204);
        auth("GET", "/admin/users/" + user.id(), null, 404);
    }

    private static int execute(Connection db, String sql, UUID id) throws SQLException {
        try (var statement = db.prepareStatement(sql)) { statement.setObject(1, id); return statement.executeUpdate(); }
    }
    private static boolean containsId(JsonNode rows, String id) { for (var row : rows) if (row.path("id").asText().equals(id)) return true; return false; }
    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static void equal(Object actual, Object expected, String label) { require(Objects.equals(actual, expected), label + " mismatch."); }
    private static void require(boolean ok, String message) { if (!ok) throw new Failure(message); }
}
