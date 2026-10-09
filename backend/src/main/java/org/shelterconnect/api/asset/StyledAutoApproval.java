package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Approval is a deterministic decision over completed, byte-bound reviews, never an AI tool call. */
final class StyledAutoApproval {
    static final String VERSION="quality-auto-approval-v1";
    private StyledAutoApproval() {}

    static boolean enabled(JsonNode policy) {
        return policy!=null && VERSION.equals(policy.path("automaticApproval").asText())
            && !policy.path("referenceOnly").asBoolean();
    }
    static boolean seed(StyledAssetStore.Job job,JsonMapper json) {
        if(!enabled(job.qualityPolicy()) || job.steps().isEmpty()
            || !StyledSpriteCodec.qualityRulesSha().equals(job.qualityPolicy().path("rulesSha256").asText()))return false;
        var step=job.steps().getFirst();var report=step.qualityReport();
        // Versioned tail bindings are checked centrally by StyledSeedQualityAgent.passed below.
        if(!step.label().equals("character") || !step.action().equals("BASE") || !step.status().equals("SUCCEEDED")
            || step.result()==null || !hashes(step.result().path("hashes"))
            || !StyledSeedQualityAgent.passed(report,step.result().path("hashes"),job.qualityPolicy())
            || !report(report,StyledSeedQualityAgent.VERSION) || !sha(report.path("photoSha256"))
            || !lessons(report,json) || !report.path("identity").asText().equals("PASS")
            || !report.path("edgeDirections").isArray() || !report.path("edgeDirections").isEmpty()
            || !report.path("views").isArray() || report.path("views").size()!=4)return false;
        var seen=new HashSet<String>();
        for(var view:report.path("views")) {
            String d=view.path("direction").asText(),read=view.path("readability").asText(),style=view.path("style").asText();
            if(!StyledSpriteCodec.DIRECTIONS.contains(d) || !seen.add(d))return false;
            if(d.equals("north")?(!read.equals("NOT_VISIBLE") || !Set.of("NOT_VISIBLE","PASS").contains(style)):
                (!read.equals("PASS") || !style.equals("PASS")))return false;
        }
        return true;
    }
    static boolean pack(StyledAssetStore.Job job,JsonMapper json) {
        if(!job.complete() || !seed(job,json) || job.seedReview()==null
            || !job.seedReview().path("hashes").equals(job.steps().getFirst().result().path("hashes")))return false;
        if(VERSION.equals(job.seedReview().path("version").asText())
            && !digest(job.steps().getFirst().qualityReport(),json).equals(job.seedReview().path("reportSha256").asText()))return false;
        for(var step:job.steps().subList(1,job.steps().size())) {
            var r=step.qualityReport();var result=step.result();
            if(!step.label().equals(step.action().toLowerCase(Locale.ROOT)+"-"+step.direction())
                || !report(r,StyledQualityAgent.VERSION) || !sha(result.path("sha256"))
                || !result.path("sha256").equals(r.path("inputSha256"))
                || !job.steps().getFirst().result().path("hashes").equals(r.path("seedHashes"))
                || !step.action().equals(r.path("action").asText()) || !step.direction().equals(r.path("direction").asText())
                || !lessons(r,json))return false;
            for(String field:List.of("edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
                if(!r.path(field).isArray() || !r.path(field).isEmpty())return false;
            if(StyledRecovery.enabled(job.qualityPolicy()) && (!StyledRecovery.VERSION.equals(r.path("recoveryVersion").asText())
                || !r.path("firstFrameUnchanged").asBoolean() || result.path("frameSize").asInt()!=40
                || !sha(result.path("motionSeedSha256")) || !result.path("motionSeedSha256").equals(r.path("motionSeedSha256"))
                || !result.path("frameHashes").isArray() || result.path("frameHashes").size()!=9
                || !result.path("frameHashes").equals(r.path("frameHashes"))))return false;
            if(result.has("rawEdit") && (!sha(result.at("/rawEdit/sha256"))
                || !result.at("/rawEdit/sha256").equals(r.path("rawEditSha256"))
                || !report(r.path("rawEditReview"),StyledQualityAgent.VERSION)
                || !report(r.path("restoredReview"),StyledQualityAgent.VERSION)))return false;
        }
        return true;
    }
    private static boolean report(JsonNode report,String version) {
        if(report==null || !version.equals(report.path("version").asText())
            || !report.path("passed").isBoolean() || !report.path("passed").asBoolean()
            || !report.path("issues").isArray() || !report.path("issues").isEmpty()
            || report.path("model").asText().isBlank()
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText()))return false;
        try {Instant.parse(report.path("reviewedAt").asText());return true;}catch(RuntimeException invalid){return false;}
    }
    private static boolean lessons(JsonNode report,JsonMapper json) {
        return report.path("learnedLessons").isArray()
            && digest(report.path("learnedLessons"),json).equals(report.path("lessonsSha256").asText());
    }
    private static boolean hashes(JsonNode hashes) {
        return hashes.isObject() && hashes.size()==4 && StyledSpriteCodec.DIRECTIONS.stream().allMatch(d->sha(hashes.path(d)));
    }
    private static boolean sha(JsonNode value) {return value.isString() && value.asText().matches("[a-f0-9]{64}");}
    static String digest(JsonNode value,JsonMapper json) {return StyledSpriteCodec.sha(json.writeValueAsBytes(value));}
    static JsonNode seedEvidence(StyledAssetStore.Job job,JsonMapper json) {
        var step=job.steps().getFirst();
        return json.valueToTree(Map.of("version",VERSION,"actor","SYSTEM","decision","APPROVE","stage","BASE",
            "hashes",step.result().path("hashes"),"reportSha256",digest(step.qualityReport(),json),
            "model",step.qualityReport().path("model").asText(),"reviewedAt",Instant.now().toString(),
            "rulesSha256",job.qualityPolicy().path("rulesSha256").asText(),"photoSha256",step.qualityReport().path("photoSha256").asText()));
    }
    static JsonNode packEvidence(StyledAssetStore.Job job,JsonMapper json) {
        var evidence=json.createObjectNode().put("version",VERSION).put("actor","SYSTEM").put("decision","APPROVE")
            .put("stage","FINAL").put("jobId",job.id().toString()).put("approvedAt",Instant.now().toString())
            .put("rulesSha256",job.qualityPolicy().path("rulesSha256").asText());
        evidence.set("seedReview",job.seedReview());evidence.set("actionPlan",json.valueToTree(job.actionPlan()));
        var steps=evidence.putArray("steps");
        for(var step:job.steps())steps.add(json.valueToTree(Map.of("label",step.label(),"resultSha256",digest(step.result(),json),
            "reportSha256",digest(step.qualityReport(),json),"model",step.qualityReport().path("model").asText(),
            "reviewedAt",step.qualityReport().path("reviewedAt").asText())));
        return evidence;
    }
}
