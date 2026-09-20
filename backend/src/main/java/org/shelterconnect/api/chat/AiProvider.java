package org.shelterconnect.api.chat;

public interface AiProvider {
	AiTypes.Generated generate(AiTypes.Context context);
}
