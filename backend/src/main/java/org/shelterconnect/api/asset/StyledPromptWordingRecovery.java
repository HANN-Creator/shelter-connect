package org.shelterconnect.api.asset;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/** Bounded equivalent wording, followed by the existing independent full-source audit. */
final class StyledPromptWordingRecovery {
    static final String VERSION="equivalent-wording-v1";
    private StyledPromptWordingRecovery() {}

    static Optional<StyledPromptSpacingRecovery.Recovery> recover(AssetException failure,int budget) {
        if(!failure.code.equals("LESSON_PROMPT_INVALID") || failure.diagnostics==null
            || !failure.diagnostics.isObject())return Optional.empty();
        var attempts=failure.diagnostics.path("promptDraftFailures");
        if(!attempts.isArray())return Optional.empty();
        for(int i=attempts.size()-1;i>=0;i--) {
            var original=attempts.get(i).path("draft");
            var errors=StyledLessonPromptAgent.formatErrors(original,budget);
            if(errors.size()!=1 || !errors.getFirst().startsWith("TOO_LONG:"))continue;
            String before=original.path("prompt").asText();
            // Complete phrases only. Never truncate, remove a clause/rule, change
            // a number/negation, or shorten approved/optional/normal qualifiers.
            String after=before.replaceAll("(?<=[,;:!?.]) +(?=[A-Za-z])","")
                .replace("native dog pixel size","native pixel size")
                .replace("including final hold","incl. final hold")
                .replace("no props or text","no props/text")
                .replace("across Every frame","in each frame")
                .replace("initial pose","start pose");
            if(before.equals(after))continue;
            var draft=(ObjectNode)original.deepCopy();draft.put("prompt",after);
            if(!StyledLessonPromptAgent.formatErrors(draft,budget).isEmpty())continue;
            var audit=(ObjectNode)failure.diagnostics.deepCopy();
            audit.putObject("wordingRecovery").put("version",VERSION).put("selectedAttempt",i+1)
                .put("budget",budget).put("originalCharacters",before.length()).put("characters",after.length())
                .put("originalSha256",sha(before)).put("sha256",sha(after)).set("draft",draft);
            return Optional.of(new StyledPromptSpacingRecovery.Recovery(draft,audit));
        }
        return Optional.empty();
    }
    private static String sha(String text){return StyledSpriteCodec.sha(text.getBytes(StandardCharsets.UTF_8));}
}
