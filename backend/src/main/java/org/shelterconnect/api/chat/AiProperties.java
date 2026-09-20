package org.shelterconnect.api.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AiProperties {
	private final boolean enabled;
	private final String apiKey,model;
	private final int timeoutSeconds;
	public AiProperties(@Value("${app.ai.enabled:false}") boolean enabled,
			@Value("${app.ai.api-key:}") String apiKey,
			@Value("${app.ai.model:gpt-5.6-luna}") String model,
			@Value("${app.ai.timeout-seconds:30}") int timeoutSeconds) {
		if(enabled && apiKey.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY is required when AI_ENABLED=true");
		if(!model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || timeoutSeconds<5 || timeoutSeconds>60)
			throw new IllegalArgumentException("Check OPENAI_MODEL and AI_TIMEOUT_SECONDS (5..60)");
		this.enabled=enabled;this.apiKey=apiKey;this.model=model;this.timeoutSeconds=timeoutSeconds;
	}
	public boolean enabled() { return enabled; }
	String apiKey() { return apiKey; }
	public String model() { return model; }
	public int timeoutSeconds() { return timeoutSeconds; }
	public int leaseSeconds() { return timeoutSeconds+60; }
}
