package org.shelterconnect.api.asset;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Recover completed, over-budget drafts without deleting or replacing any content. */
final class StyledPromptSpacingRecovery {
    static final String VERSION="punctuation-spacing-v1";
    record Recovery(JsonNode draft,JsonNode diagnostics) {}

    static Optional<Recovery> recover(AssetException failure,int budget) {
        if(!failure.code.equals("LESSON_PROMPT_INVALID") || failure.diagnostics==null
            || !failure.diagnostics.isObject())return Optional.empty();
        var attempts=failure.diagnostics.path("promptDraftFailures");
        if(!attempts.isArray())return Optional.empty();
        // Prefer the newest complete response that fits. Every candidate still needs independent semantic review.
        for(int i=attempts.size()-1;i>=0;i--) {
            var original=attempts.get(i).path("draft");
            var errors=StyledLessonPromptAgent.formatErrors(original,budget);
            if(errors.size()!=1 || !errors.getFirst().startsWith("TOO_LONG:"))continue;
            String before=original.path("prompt").asText();
            // Leave word/number boundaries, numeric punctuation, comparisons and all non-space characters intact.
            String after=before.replaceAll("(?<=[,;:!?.]) +(?=[A-Za-z])","");
            if(before.equals(after))continue;
            var draft=(ObjectNode)original.deepCopy();draft.put("prompt",after);
            if(!StyledLessonPromptAgent.formatErrors(draft,budget).isEmpty())continue;
            var audit=(ObjectNode)failure.diagnostics.deepCopy();
            var change=audit.putObject("spacingRecovery").put("version",VERSION).put("selectedAttempt",i+1)
                .put("budget",budget).put("originalCharacters",before.length()).put("characters",after.length())
                .put("originalSha256",sha(before)).put("sha256",sha(after));
            change.set("draft",draft);
            return Optional.of(new Recovery(draft,audit));
        }
        return Optional.empty();
    }
    private static String sha(String text){return StyledSpriteCodec.sha(text.getBytes(StandardCharsets.UTF_8));}
}
