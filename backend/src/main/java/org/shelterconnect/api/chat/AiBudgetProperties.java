package org.shelterconnect.api.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiBudgetProperties {
    private final int userMinute, userDay, globalDay, concurrent;
    public AiBudgetProperties(@Value("${app.ai.user-minute-limit:6}") int userMinute,
                              @Value("${app.ai.user-daily-limit:60}") int userDay,
                              @Value("${app.ai.global-daily-limit:500}") int globalDay,
                              @Value("${app.ai.concurrent-limit:2}") int concurrent) {
        if (userMinute < 1 || userMinute > 60 || userDay < 1 || userDay > 1000
                || globalDay < 1 || globalDay > 10000 || concurrent < 1 || concurrent > 10)
            throw new IllegalArgumentException("AI usage limits are outside the supported range");
        this.userMinute=userMinute; this.userDay=userDay; this.globalDay=globalDay; this.concurrent=concurrent;
    }
    public int userMinute() { return userMinute; }
    public int userDay() { return userDay; }
    public int globalDay() { return globalDay; }
    public int concurrent() { return concurrent; }
}
