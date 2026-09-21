package org.shelterconnect.sample;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginStorageCheckTest {
    private Map<String, String> settings() {
        return new HashMap<>(Map.of("AUTH_CHECK_PROJECT_REF", "a".repeat(20),
                "SUPABASE_URL", "https://" + "a".repeat(20) + ".supabase.co",
                "DB_USERNAME", "postgres." + "a".repeat(20),
                "DB_URL", "jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require",
                "DB_PASSWORD", "test-password-never-print", "SUPABASE_SECRET_KEY", "sb_secret_test_only"));
    }

    @Test void requiresExplicitMatchingProjectBeforeAnySideEffect() {
        LoginStorageCheck.validateTarget(settings());
        for (String field : new String[] {"AUTH_CHECK_PROJECT_REF", "SUPABASE_URL", "DB_USERNAME"}) {
            var env = settings(); env.put(field, "different");
            assertThatThrownBy(() -> new LoginStorageCheck(env)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void refusesUnsafeDatabaseTargetsAndCredentialBearingUrls() {
        for (String url : new String[] {"jdbc:postgresql://localhost/test", settings().get("DB_URL").replace("require", "disable"),
                settings().get("DB_URL").replace(":5432", ":6543"), settings().get("DB_URL") + "&password=private-value"}) {
            var env = settings(); env.put("DB_URL", url);
            assertThatThrownBy(() -> new LoginStorageCheck(env)).isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("private-value");
        }
    }

    @Test void missingCredentialsFailWithoutEchoingTheirValues() {
        var env = settings(); env.put("SUPABASE_SECRET_KEY", "private-invalid-secret");
        assertThatThrownBy(() -> new LoginStorageCheck(env)).hasMessageNotContaining("private-invalid-secret");
        env.put("SUPABASE_SECRET_KEY", "sb_secret_test_only"); env.put("DB_PASSWORD", "");
        assertThatThrownBy(() -> new LoginStorageCheck(env)).hasMessageContaining("password is required");
    }
}
