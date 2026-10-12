package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Product policy: visual findings are advisory. This never changes a failed observation to PASS. */
final class StyledVisualWarnings {
    static final String VERSION="visual-warnings-v1";
    private StyledVisualWarnings() {}
    static boolean enabled(JsonNode policy) {
        return policy!=null && VERSION.equals(policy.path("visualWarnings").asText())
            && !policy.path("referenceOnly").asBoolean();
    }
    static ObjectNode report(JsonMapper json,JsonNode observation,String error) {
        var out=json.createObjectNode().put("version",VERSION).put("enforcement","WARN_ONLY")
            .put("reviewedAt",Instant.now().toString()).put("model",observation.path("model").asText("unavailable"));
        out.set("observation",observation);
        var warnings=out.putArray("qualityWarnings");
        if(error!=null)warnings.addObject().put("code","REVIEW_UNAVAILABLE").put("note","자동 관찰을 완료하지 못했습니다. 이미지를 직접 확인해 주세요.").put("reason",error);
        else if(observation.path("warnings").isArray())warnings.addAll((tools.jackson.databind.node.ArrayNode)observation.path("warnings"));
        out.put("reviewStatus",error!=null?"UNAVAILABLE":warnings.isEmpty()?"CLEAR":"WARNING");
        out.put("passed",error==null && warnings.isEmpty());
        return out;
    }
    static JsonNode warnings(StyledAssetStore.Step step,JsonMapper json) {
        var r=step.qualityReport();var out=json.createArrayNode();if(r==null)return out;
        if(r.path("qualityWarnings").isArray())out.addAll((tools.jackson.databind.node.ArrayNode)r.path("qualityWarnings"));
        if(r.path("promptWarnings").isArray())out.addAll((tools.jackson.databind.node.ArrayNode)r.path("promptWarnings"));
        // Preserve strict-era observations when an existing job explicitly changes policy.
        if(!VERSION.equals(r.path("version").asText())) {
            for(var issue:r.path("issues"))out.addObject().put("code",issue.asText()).put("note",r.path("note").asText("이전 자동 검수에서 확인이 필요하다고 기록한 부분입니다.")).set("frames",r.path("frames"));
            if(!r.path("passed").asBoolean() && out.isEmpty())out.addObject().put("code","PREVIOUS_REVIEW_UNRESOLVED").put("note","이전 검수의 미확정 결과입니다. 원본을 직접 확인해 주세요.");
        }
        return out;
    }
    static boolean seed(StyledAssetStore.Job job,JsonMapper json) {
        if(!enabled(job.qualityPolicy()) || job.steps().isEmpty())return false;
        var s=job.steps().getFirst();var r=s.qualityReport();var m=s.result();
        if(!s.label().equals("character") || !s.action().equals("BASE") || !s.status().equals("SUCCEEDED")
            || m==null || m.path("quarantined").asBoolean() || r==null || !hashes(m.path("hashes"))
            || !validReport(r,true) || !rules(job,r) || !sha(r.path("photoSha256")) || !timestamp(r.path("reviewedAt")))return false;
        if(StyledSpriteCodec.DIRECTIONS.stream().anyMatch(d->!m.at("/keys/"+d).asText().startsWith(job.dogId()+"/"+job.id()+"/native-32/directions/")))return false;
        return r.path("inputSha256").asText().equals(StyledSeedQualityAgent.hashBinding(m.path("hashes")))
            && !r.path("status").asText().equals("STARTED") && r.path("passed").isBoolean()
            && r.path("learnedLessons").isArray()
            && StyledAutoApproval.digest(r.path("learnedLessons"),json).equals(r.path("lessonsSha256").asText());
    }
    static boolean pack(StyledAssetStore.Job job,JsonMapper json) {
        if(!seed(job,json) || !job.complete() || job.seedReview()==null
            || !job.seedReview().path("hashes").equals(job.steps().getFirst().result().path("hashes")))return false;
        if(StyledAutoApproval.VERSION.equals(job.seedReview().path("version").asText())
            && !StyledAutoApproval.digest(job.steps().getFirst().qualityReport(),json).equals(job.seedReview().path("reportSha256").asText()))return false;
        for(var s:job.steps().subList(1,job.steps().size())) {
            var r=s.qualityReport();var m=s.result();
            if(r==null || m==null || !validReport(r,false) || !s.label().equals(s.action().toLowerCase(Locale.ROOT)+"-"+s.direction())
                || !m.path("key").asText().startsWith(job.dogId()+"/"+job.id()+"/native-32/sheets/") || !rules(job,r) || !timestamp(r.path("reviewedAt")) || !r.path("passed").isBoolean()
                || !sha(m.path("sha256")) || !m.path("sha256").equals(r.path("inputSha256"))
                || !s.action().equals(r.path("action").asText()) || !s.direction().equals(r.path("direction").asText())
                || !job.steps().getFirst().result().path("hashes").equals(r.path("seedHashes"))
                || m.path("frameSize").asInt()!=40 || m.path("frameCount").asInt()!=9
                || !sha(m.path("motionSeedSha256")) || !m.path("motionSeedSha256").equals(r.path("motionSeedSha256"))
                || !m.path("frameHashes").isArray() || m.path("frameHashes").size()!=9
                || m.path("frameHashes").valueStream().anyMatch(h->!sha(h)) || !m.path("frameHashes").equals(r.path("frameHashes"))
                || !r.path("learnedLessons").isArray()
                || !StyledAutoApproval.digest(r.path("learnedLessons"),json).equals(r.path("lessonsSha256").asText()))return false;
        }
        return true;
    }
    private static boolean validReport(JsonNode r,boolean seed) {
        if(r.path("model").asText().isBlank())return false;
        if(VERSION.equals(r.path("version").asText()))return "WARN_ONLY".equals(r.path("enforcement").asText())
            && Set.of("CLEAR","WARNING","UNAVAILABLE").contains(r.path("reviewStatus").asText()) && r.path("qualityWarnings").isArray();
        return (seed?StyledSeedQualityAgent.VERSION:StyledQualityAgent.VERSION).equals(r.path("version").asText());
    }
    private static boolean rules(StyledAssetStore.Job j,JsonNode r) {
        return StyledSpriteCodec.qualityRulesSha().equals(j.qualityPolicy().path("rulesSha256").asText())
            && r.path("rulesSha256").equals(j.qualityPolicy().path("rulesSha256"));
    }
    private static boolean hashes(JsonNode h) {return h.isObject() && h.size()==4 && StyledSpriteCodec.DIRECTIONS.stream().allMatch(d->sha(h.path(d)));}
    private static boolean sha(JsonNode h) {return h.isString() && h.asText().matches("[a-f0-9]{64}");}
    private static boolean timestamp(JsonNode t) {try{Instant.parse(t.asText());return true;}catch(RuntimeException e){return false;}}
    static void evidence(ObjectNode out) {
        out.put("visualReviewMode","WARN_ONLY").put("approvalBasis","COMPLETE_BOUND_ASSETS_VISUAL_FINDINGS_ADVISORY");
    }
}
