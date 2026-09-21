package org.shelterconnect.sample;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.shelterconnect.api.chat.AiProperties;
import tools.jackson.databind.JsonNode;

/** Bounded manual check. Never runs in build/CI. Uses only the checked-in fictional fixture. */
public final class AiLiveCheck extends LoginStorageCheck {
    private static final String DOG_PREFIX = "02200000-0000-4000-8000-00000000000";
    private static final String OBS_PREFIX = "02300000-0000-4000-8000-000000000";
    private static final Pattern USAGE = Pattern.compile("AI_USAGE model=([A-Za-z0-9._-]+) input_tokens=(\\d+) cached_input_tokens=(\\d+) output_tokens=(\\d+) reasoning_tokens=(\\d+) duration_ms=(\\d+)");
    private final Map<String, String> ai;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<String> qualityFailures = new ArrayList<>();
    private final Map<String, String> sessions = new HashMap<>();
    private final Map<String, JsonNode> observations = new HashMap<>();
    private final Map<String, String> dogNames = new HashMap<>();
    private final Map<String, String> cachedReplies = new LinkedHashMap<>();
    private String accountA;

    AiLiveCheck(Map<String, String> env) {
        super(env, "q02");
        require("gpt-5.6-luna".equals(env.get("OPENAI_MODEL")), "Use the agreed gpt-5.6-luna model for this check.");
        String key = env.getOrDefault("OPENAI_API_KEY", "");
        require(key.matches("sk-[A-Za-z0-9_-]+"), "Missing local OpenAI API key; no external call was made.");
        new AiProperties(true, key, env.get("OPENAI_MODEL"), Integer.parseInt(env.getOrDefault("AI_TIMEOUT_SECONDS", "30")));
        ai = Map.of("OPENAI_API_KEY", key, "OPENAI_MODEL", env.get("OPENAI_MODEL"),
                "AI_TIMEOUT_SECONDS", env.getOrDefault("AI_TIMEOUT_SECONDS", "30"));
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "Use scripts/check_ai_live.py with an explicit development project.");
            new AiLiveCheck(System.getenv()).run();
        } catch (Exception failure) {
            // Our constant diagnostics only. Never echo provider bodies, keys or credentials.
            String message = failure.getMessage();
            if (failure instanceof IllegalArgumentException || message == null
                    || !failure.getClass().getName().startsWith("org.shelterconnect.sample."))
                message = "AI check failed. Inspect the private check report; no secrets were printed.";
            System.err.println(message);
            System.exit(1);
        }
    }

    @Override protected void verify() throws Exception {
        verifyFictionalData();
        var cases = json.readTree(Files.readString(Path.of("sample-data/ai/cases.json")));
        require(cases.isArray() && cases.size() == 10, "Expected the reviewed ten-case suite.");
        try {
            var invalid = new HashMap<>(ai);
            invalid.put("OPENAI_API_KEY", "sk-q02-intentionally-invalid-test-key");
            startServer(invalid);
            var a = createUser("a"); var b = createUser("b");
            String tokenA = login(a), tokenB = login(b);
            accountA = register(tokenA); register(tokenB);
            error("POST", "/v1/dogs/" + DOG_PREFIX + "5/chat-sessions", tokenA, null, 404, "DOG_NOT_FOUND");
            String session = session(tokenA, "1");
            String hello = save(tokenA, session, "hello", "안녕");
            int before = callCount();
            var greeting = api("POST", replyPath(session, hello), tokenA, null, 201).path("data");
            equal(greeting.path("processingStatus").asText(), "COMPLETED", "Local greeting");
            equal(callCount(), before, "Greeting must not invoke the provider");
            String message = save(tokenA, session, "retry", "공을 굴려주면 어떻게 놀았어?");
            var failed = api("POST", replyPath(session, message), tokenA, null, 200).path("data");
            equal(failed.path("failureCode").asText(), "AI_AUTH_FAILED", "Real invalid-key failure");
            equal(failed.path("processingStatus").asText(), "FAILED", "Failed request persistence");
            require(failed.path("reply").isNull() && failed.path("retryable").asBoolean(), "Failed reply must not contain an answer.");
            int calls = callCount();
            equal(calls, before + 1, "Exactly one failed provider request");
            equal(attempts(message), 1, "Initial failed attempt count");
            equal(api("POST", replyPath(session, message), tokenA, null, 200).path("data"), failed, "Failure without retry");
            equal(callCount(), calls, "Failure must not automatically retry");
            stopServer(); startServer(ai);
            tokenA = login(a); tokenB = login(b);
            equal(api("POST", replyPath(session, message), tokenA, null, 200).path("data"), failed, "Failure persists after restart");
            equal(callCount(), calls, "Normal key must not implicitly retry a failed request");
            var recovery = json.readTree("{\"id\":\"retry_recovery\",\"dog\":\"1\",\"question\":\"공을 굴려주면 어떻게 놀았어?\",\"expected\":\"grounded\",\"evidence\":\"002\",\"review\":\"실제 키 오류 뒤 명시적 재시도. 공을 따라갔지만 항상 가져오지는 않았다는 기록을 소개.\"}");
            checkReply(recovery, tokenA, tokenB, session, message, true);
            equal(attempts(message), 2, "Explicit retry increments attempt count once");
            System.out.println("PASS real 401 failure, persisted failure, no automatic retry, explicit retry and stored evidence.");
            for (var item : cases) {
                session = session(tokenA, item.path("dog").asText());
                message = save(tokenA, session, item.path("id").asText(), item.path("question").asText());
                checkReply(item, tokenA, tokenB, session, message, false);
            }
            int finalCalls = callCount();
            equal(finalCalls, 12, "Bounded provider calls: one invalid-key request and eleven successful responses");
            stopServer(); startServer(ai); tokenA = login(a); tokenB = login(b);
            for (var stored : cachedReplies.entrySet()) {
                var response = api("POST", stored.getKey(), tokenA, null, 200).path("data");
                equal(response.path("reply").path("id").asText(), stored.getValue(), "Persisted cached answer after restart");
                error("POST", stored.getKey(), tokenB, null, 404, "CHAT_NOT_FOUND");
            }
            equal(callCount(), finalCalls, "Restart reads must not generate new answers");
            var metrics = usage();
            require(metrics.size() == 11, "Token usage unavailable for one or more real calls; do not report zero cost.");
            writeReport(true);
            System.out.println("PASS stored replies/evidence, cached replay, ownership and restart. Local report: " + work.resolve("ai-report.json"));
            require(qualityFailures.isEmpty(), "Quality expectations failed. Review ai-report.json; do not mark the task complete.");
            System.out.println("Mechanical checks passed. Review every answer against its observations before accepting conversational quality.");
        } finally {
            writeReport(false); // Preserve partial results if a remote call or later assertion fails.
        }
    }

    private String session(String token, String dog) throws Exception {
        if (!sessions.containsKey(dog)) {
            String id = api("POST", "/v1/dogs/" + DOG_PREFIX + dog + "/chat-sessions", token, null, 201).path("data").path("id").asText();
            sessions.put(dog, id);
        }
        return sessions.get(dog);
    }

    private String save(String token, String session, String id, String text) throws Exception {
        return api("POST", "/v1/chat-sessions/" + session + "/messages", token,
                Map.of("clientMessageId", marker + "-" + id, "text", text), 201).path("data").path("id").asText();
    }

    private void checkReply(JsonNode item, String a, String b, String session, String message, boolean retry) throws Exception {
        String path = replyPath(session, message);
        int before = callCount();
        long started = System.nanoTime();
        var reply = api("POST", path, a, retry ? Map.of("retry", true) : null, 200, 201).path("data");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        var row = new LinkedHashMap<String, Object>();
        row.put("case", item.path("id").asText()); row.put("dog", dogNames.get(DOG_PREFIX + item.path("dog").asText()));
        row.put("question", item.path("question").asText()); row.put("reviewCriteria", item.path("review").asText());
        row.put("status", reply.path("processingStatus").asText()); row.put("elapsedMs", elapsedMs);
        if (!reply.path("processingStatus").asText().equals("COMPLETED")) {
            row.put("failureCode", reply.path("failureCode").asText()); results.add(row); writeReport(false);
            throw new IllegalStateException("Real provider failed; inspect sanitized failureCode in the local report.");
        }
        equal(callCount(), before + 1, "One real provider call per question");
        var answer = reply.path("reply"); String replyId = answer.path("id").asText();
        row.put("answer", answer.path("text").asText()); row.put("needsShelterConfirmation", answer.path("needsShelterConfirmation").asBoolean());
        var evidence = evidence(replyId, DOG_PREFIX + item.path("dog").asText());
        row.put("evidence", evidence); results.add(row);
        var observed = usage(); if (!observed.isEmpty()) row.put("usage", observed.get(observed.size() - 1));
        boolean unknown = answer.path("needsShelterConfirmation").asBoolean();
        if (item.path("expected").asText().equals("unknown")) {
            if (!unknown || !evidence.isEmpty()) qualityFailures.add(item.path("id").asText() + ": expected shelter confirmation without factual evidence");
        } else {
            String expected = OBS_PREFIX + item.path("evidence").asText();
            if (unknown || evidence.stream().noneMatch(e -> expected.equals(e.get("id"))))
                qualityFailures.add(item.path("id").asText() + ": expected the relevant confirmed observation");
        }
        equal(answer.path("replyToMessageId").asText(), message, "Saved answer routing");
        equal(api("POST", path, a, null, 200).path("data"), reply, "Cached answer must match");
        equal(api("POST", path, a, Map.of("retry", true), 200).path("data"), reply, "Completed retry must reuse answer");
        error("POST", path, b, null, 404, "CHAT_NOT_FOUND");
        equal(callCount(), before + 1, "Duplicate and cross-user requests must not call the provider");
        var saved = api("GET", "/v1/chat-sessions/" + session + "/messages", a, null, 200).path("data");
        boolean found = false; for (var m : saved) if (m.path("id").asText().equals(replyId)) { equal(m, answer, "Re-read stored answer"); found = true; }
        require(found, "Saved answer absent from message list.");
        cachedReplies.put(path, replyId); writeReport(false);
        System.out.println("Checked " + item.path("id").asText() + " (answer/evidence retained locally for review).");
    }

    private List<Map<String, String>> evidence(String replyId, String dog) throws Exception {
        var evidence = new ArrayList<Map<String, String>>();
        try (var db = connection(); var query = db.prepareStatement("SELECT e.observation_id,e.dog_id,e.observation_snapshot FROM shelter.chat_message_observations e JOIN shelter.chat_messages m ON m.id=e.message_id JOIN shelter.chat_sessions s ON s.id=m.session_id WHERE e.message_id=? AND s.user_id=?")) {
            query.setObject(1, UUID.fromString(replyId)); query.setObject(2, UUID.fromString(accountA));
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1); var fixture = observations.get(id);
                    require(fixture != null && fixture.path("status").asText().equals("CONFIRMED"), "Non-confirmed or unknown evidence stored.");
                    equal(rows.getString(2), dog, "Evidence dog"); equal(fixture.path("dogId").asText(), dog, "Fixture evidence dog");
                    var snapshot = json.readTree(rows.getString(3));
                    equal(snapshot.path("content").asText(), fixture.path("content").asText(), "Saved evidence snapshot");
                    evidence.add(Map.of("id", id, "content", fixture.path("content").asText()));
                }
            }
        }
        return evidence;
    }

    private int attempts(String message) throws Exception {
        try (var db = connection(); var q = db.prepareStatement("SELECT generation_attempts FROM shelter.chat_messages m JOIN shelter.chat_sessions s ON s.id=m.session_id WHERE m.id=? AND s.user_id=?")) {
            q.setObject(1, UUID.fromString(message)); q.setObject(2, UUID.fromString(accountA));
            try (var rows = q.executeQuery()) { require(rows.next(), "Missing own generation request."); return rows.getInt(1); }
        }
    }

    private void verifyFictionalData() throws Exception {
        var fixture = json.readTree(Files.readString(Path.of("sample-data/dataset.json")));
        require(fixture.path("fictional").asBoolean(), "Only the fictional fixture may be sent in this check.");
        for (var dog : fixture.path("dogs")) dogNames.put(dog.path("id").asText(), dog.path("name").asText());
        for (var o : fixture.path("observations")) observations.put(o.path("id").asText(), o);
        try (var db = connection()) {
            db.setReadOnly(true);
            for (var dog : fixture.path("dogs")) {
                String id = dog.path("id").asText();
                try (var q = db.prepareStatement("SELECT name FROM shelter.dogs WHERE id=?")) {
                    q.setObject(1, UUID.fromString(id)); try (var rows = q.executeQuery()) {
                        require(rows.next(), "Fictional dog missing."); equal(rows.getString(1), dog.path("name").asText(), "Fictional dog identity");
                    }
                }
                try (var q = db.prepareStatement("SELECT id,content,status FROM shelter.dog_observations WHERE dog_id=?")) {
                    q.setObject(1, UUID.fromString(id)); try (var rows = q.executeQuery()) {
                        int count=0;
                        while(rows.next()) {
                            var expected = observations.get(rows.getString(1)); require(expected != null, "Unexpected observation: refusing to send unreviewed data.");
                            equal(expected.path("dogId").asText(), id, "Fictional observation dog");
                            equal(rows.getString(2), expected.path("content").asText(), "Fictional observation content");
                            equal(rows.getString(3), expected.path("status").asText(), "Fictional observation status"); count++;
                        }
                        equal(count, 5, "Complete fictional dog observation fixture");
                    }
                }
            }
        }
    }

    private int callCount() throws Exception {
        Path log = work.resolve("server.log");
        if (!Files.exists(log)) return 0;
        return (int) Files.readAllLines(log).stream().filter(s -> s.contains("AI_CALL status=")).count();
    }

    private List<Map<String, Object>> usage() throws Exception {
        var usage = new ArrayList<Map<String, Object>>(); Path log = work.resolve("server.log");
        if (!Files.exists(log)) return usage;
        for (String line : Files.readAllLines(log)) {
            var m = USAGE.matcher(line); if (!m.find()) continue;
            usage.add(Map.of("model",m.group(1),"inputTokens",Long.parseLong(m.group(2)),"cachedInputTokens",Long.parseLong(m.group(3)),
                    "outputTokens",Long.parseLong(m.group(4)),"reasoningTokens",Long.parseLong(m.group(5)),"providerMs",Long.parseLong(m.group(6))));
        }
        return usage;
    }

    private void writeReport(boolean complete) throws Exception {
        if (work == null) return;
        var file = work.resolve("ai-report.json");
        boolean previouslyComplete = Files.exists(file) && json.readTree(Files.readString(file)).path("mechanicalChecksComplete").asBoolean();
        var report = Map.of("model",ai.get("OPENAI_MODEL"),"checkedAt",Instant.now().toString(),
                "mechanicalChecksComplete",complete || previouslyComplete,"manualReviewRequired",true,
                "qualityFailures",qualityFailures,"cases",results,"usage",usage());
        Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String replyPath(String session, String message) { return "/v1/chat-sessions/" + session + "/messages/" + message + "/reply"; }
}
