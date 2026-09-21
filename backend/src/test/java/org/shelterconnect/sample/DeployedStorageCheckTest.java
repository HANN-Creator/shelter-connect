package org.shelterconnect.sample;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeployedStorageCheckTest {
    @Test void acceptsOnlyAnExplicitRenderHttpsOrigin() {
        assertThat(DeployedStorageCheck.validateOrigin("https://shelter-connect-dev.onrender.com"))
                .isEqualTo("https://shelter-connect-dev.onrender.com");
        for (String value : new String[] {"", "http://shelter-connect-dev.onrender.com", "https://example.com",
                "https://shelter-connect-dev.onrender.com.evil.test", "https://shelter-connect-dev.onrender.com:443",
                "https://shelter-connect-dev.onrender.com/", "https://secret@server.onrender.com",
                "https://server.onrender.com?token=secret", "https://server.onrender.com#secret"}) {
            assertThatThrownBy(() -> DeployedStorageCheck.validateOrigin(value)).isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("secret");
        }
    }
}
