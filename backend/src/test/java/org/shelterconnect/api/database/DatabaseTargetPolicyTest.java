package org.shelterconnect.api.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DatabaseTargetPolicyTest {
    private final String prefix="jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?";
    private final String user="shelter_runtime.abcdefghijklmnopqrst";
    @Test void acceptsValidatedTlsAndDisposableLocalDatabase() {
        assertThatCode(()->DatabaseTargetPolicy.validate(prefix+"sslmode=verify-full&sslrootcert=/app/certs/supabase.crt",user)).doesNotThrowAnyException();
        assertThatCode(()->DatabaseTargetPolicy.validate("jdbc:postgresql://127.0.0.1:5432/shelter_test","shelter_ci")).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={
        "sslmode=require", "sslmode=disable", "sslmode=verify-ca&sslrootcert=/tmp/ca",
        "sslmode=verify-full", "sslmode=verify-full&sslrootcert=relative.crt",
        "sslmode=verify-full&sslrootcert=/tmp/ca&sslfactory=org.postgresql.ssl.NonValidatingFactory",
        "sslmode=verify-full&sslrootcert=/tmp/ca&sslmode=disable",
        "sslmode=verify-full&sslrootcert=/tmp/ca&password=private-value"})
    void rejectsWeakenedTlsAndUnknownConnectionOptions(String query) {
        assertThatThrownBy(()->DatabaseTargetPolicy.validate(prefix+query,user)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("private-value");
    }
    @Test void rejectsAdministrativeCredentialsAndUntrustedTargets() {
        for(String name:new String[]{"postgres.abcdefghijklmnopqrst","shelter_runtime","anon"})
            assertThatThrownBy(()->DatabaseTargetPolicy.validate(prefix+"sslmode=verify-full&sslrootcert=/tmp/ca",name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DatabaseTargetPolicy.validate(prefix.replace("pooler.supabase.com","attacker.invalid")+"sslmode=verify-full&sslrootcert=/tmp/ca",user)).isInstanceOf(IllegalArgumentException.class);
    }
}
