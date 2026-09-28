package org.shelterconnect.api.chat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiBudgetPropertiesTest {
    @Test void limitsCannotBeDisabledOrAccidentallyMadeUnbounded() {
        assertThatThrownBy(()->new AiBudgetProperties(0,60,500,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AiBudgetProperties(6,0,500,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AiBudgetProperties(6,60,10001,2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AiBudgetProperties(6,60,500,11)).isInstanceOf(IllegalArgumentException.class);
    }
}
