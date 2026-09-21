package org.shelterconnect.api.chat;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Numeric provider usage only. Never includes prompts, response text, credentials or identity data. */
record AiCallUsage(long input, long cachedInput, long output, long reasoning) {
    static Optional<AiCallUsage> read(JsonNode root) {
        var usage = root.path("usage");
        var input = usage.path("input_tokens");
        var output = usage.path("output_tokens");
        var cached = usage.path("input_tokens_details").path("cached_tokens");
        var reasoning = usage.path("output_tokens_details").path("reasoning_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber()
                || !cached.isIntegralNumber() || !reasoning.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong()
                || !cached.canConvertToLong() || !reasoning.canConvertToLong()) return Optional.empty();
        long in = input.asLong(), out = output.asLong(), cache = cached.asLong(), reason = reasoning.asLong();
        if (in < 0 || out < 0 || cache < 0 || reason < 0 || cache > in || reason > out
                || in > 2_000_000 || out > 2_000_000) return Optional.empty();
        return Optional.of(new AiCallUsage(in, cache, out, reason));
    }
}
