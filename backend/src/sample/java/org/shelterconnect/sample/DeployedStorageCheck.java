package org.shelterconnect.sample;

import java.util.Map;

/** Opt-in deployment check. Only temporary user JWTs go to the explicitly selected HTTPS API. */
public final class DeployedStorageCheck extends LoginStorageCheck {
    DeployedStorageCheck(Map<String, String> env) {
        super(env, "b14");
        base = validateOrigin(env.getOrDefault("DEPLOYMENT_CHECK_ORIGIN", ""));
    }

    static String validateOrigin(String origin) {
        require(origin.matches("https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.onrender\\.com"),
                "Select an explicit Render HTTPS origin without credentials, path, query or fragment.");
        return origin;
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "Use the documented deployment launcher.");
            new DeployedStorageCheck(System.getenv()).run();
        } catch (Exception failure) {
            System.err.println("Deployment storage check failed; inspect the private recovery record. Credentials and response bodies were not printed.");
            System.exit(1);
        }
    }

    @Override protected void verify() throws Exception {
        // Do not launch, stop, restart, or reconfigure the hosted service.
        TestUser a = createUser("a"), b = createUser("b");
        String tokenA = login(a), tokenB = login(b);
        String aId = register(tokenA), bId = register(tokenB);
        require(!aId.equals(bId), "Two logins must produce separate app users.");
        var saved = exercise(tokenA, tokenB);
        tokenA = login(a); tokenB = login(b);
        equal(api("GET", "/v1/me", tokenA, null, 200).path("data").path("id").asText(), aId, "Account after fresh login");
        equal(api("GET", "/v1/me", tokenB, null, 200).path("data").path("id").asText(), bId, "Second account after fresh login");
        equal(api("GET", "/v1/chat-sessions/" + saved.get("session"), tokenA, null, 200).path("data").path("id").asText(),
                saved.get("session"), "Stored session");
        var messages = api("GET", "/v1/chat-sessions/" + saved.get("session") + "/messages", tokenA, null, 200).path("data");
        require(messages.size() == 2, "Messages must persist exactly once.");
        var note = api("GET", "/v1/me/adoption-notes/02200000-0000-4000-8000-000000000001", tokenA, null, 200).path("data");
        equal(note.path("id").asText(), saved.get("note"), "Stored note");
        equal(note.path("updatedAt").asText(), saved.get("noteVersion"), "Stored note version");
        equal(note.path("questions").asText(), marker + " 수정한 질문", "Stored note content");
        error("GET", "/v1/chat-sessions/" + saved.get("session"), tokenB, null, 404, "CHAT_NOT_FOUND");
        System.out.println("PASS deployed HTTPS API: fresh login, stored records, ownership, conflicts and duplicate requests.");
        System.out.println("Hosted restart and AI/Storage calls are not part of this check.");
    }
}
