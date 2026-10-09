package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Select a genuinely passing raw candidate; the rejected postprocessing report remains rejected. */
final class StyledRawMotion {
    static final String VERSION="native-provider-rgba-v1";
    static final String HASH_VERSION="canonical-json-v1";
    record Candidate(JsonNode result,JsonNode report) {}
    private StyledRawMotion() {}
    static boolean derived(JsonNode r){return r!=null && VERSION.equals(r.at("/derivation/strategy").asText());}
    static boolean eligible(StyledAssetStore.Work w,JsonNode report,JsonNode result) {
        return !w.character() && StyledRecovery.enabled(w.qualityPolicy()) && StyledAutoApproval.enabled(w.qualityPolicy())
            && Set.of("WAITING","PERSISTING","CHECKING").contains(w.status())
            && (!w.status().equals("CHECKING") || StyledAssetStore.motionResumeAllowed(w))
            && StyledSpriteCodec.qualityRulesSha().equals(w.qualityPolicy().path("rulesSha256").asText())
            && result!=null && result.path("frameSize").asInt()==40 && result.has("rawEdit") && !derived(result)
            && result.at("/rawEdit/key").asText().startsWith(w.prefix()+"raw-edits/")
            && !result.path("sha256").equals(result.at("/rawEdit/sha256"))
            && report!=null && !report.path("passed").asBoolean() && !report.at("/restoredReview/passed").asBoolean()
            && report.path("inputSha256").equals(result.path("sha256"))
            && report.path("rawEditSha256").equals(result.at("/rawEdit/sha256"))
            && report.path("rulesSha256").asText().equals(StyledSpriteCodec.qualityRulesSha())
            && w.action().equals(report.path("action").asText()) && w.direction().equals(report.path("direction").asText())
            && genuinePass(report.path("rawEditReview"),w.action());
    }
    static boolean genuinePass(JsonNode r,String action) {return genuinePass(r,action,true);}
    private static boolean genuinePass(JsonNode r,String action,boolean currentRules) {
        if(!StyledQualityAgent.VERSION.equals(r.path("version").asText()) || !r.path("passed").asBoolean()
            || !StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
            || !r.path("motionDecision").asText().equals("PASS") || !r.path("referencePoseUsable").asBoolean()
            || !r.path("rulesSha256").asText().matches("[a-f0-9]{64}")
            || (currentRules && !r.path("rulesSha256").asText().equals(StyledSpriteCodec.qualityRulesSha()))
            || r.path("model").asText().isBlank() || r.has("tailGeometryReconciliation") || r.has("occlusionOriginalReport"))return false;
        try {Instant.parse(r.path("reviewedAt").asText());}catch(RuntimeException invalid){return false;}
        for(String field:List.of("issues","uncertainProperties","confirmedProperties","edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
            if(!r.path(field).isArray() || !r.path(field).isEmpty())return false;
        int count=r.path("observationCount").asInt();
        if(count<1 || count>2 || r.has("consistencyReview")!=(count==2))return false;
        for(String field:count==1?List.of("initialVision"):List.of("initialVision","consistencyReview")) {
            var observation=r.path(field);
            try {StyledMotionReview.validate(observation,action);}catch(AssetException invalid){return false;}
            if(observation.path("properties").valueStream().anyMatch(p->!p.path("state").asText().equals("PASS")
                && !(p.path("property").asText().equals("palette") && StyledAestheticPolicy.permitsPaletteWarning(r))))return false;
        }
        return true;
    }
    static Candidate candidate(StyledAssetStore.Work w,JsonNode report,JsonNode previous,byte[] raw,byte[] seed,JsonNode seeds,JsonMapper json) {
        if(!eligible(w,report,previous) || !StyledSpriteCodec.sha(raw).equals(previous.at("/rawEdit/sha256").asText())
            || !report.path("seedHashes").equals(seeds))throw invalid();
        var frames=StyledSpriteCodec.frames(raw);var r=(ObjectNode)report.path("rawEditReview").deepCopy();
        if(frames.size()!=9 || frames.stream().anyMatch(f->StyledSpriteCodec.motionFrame(f).getWidth()!=40)
            || !r.path("reviewedFrameHashes").equals(json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()))
            || !r.path("referenceFrameSha256").asText().equals(StyledSpriteCodec.sha(seed)))throw invalid();
        String sha=StyledSpriteCodec.sha(raw);var result=(ObjectNode)previous.deepCopy();result.remove("rawEdit");
        result.put("key",w.prefix()+"sheets/raw-provider/"+w.label()+"-"+sha+".png").put("sha256",sha);
        result.set("frameHashes",r.path("reviewedFrameHashes"));result.put("motionSeedSha256",StyledSpriteCodec.sha(seed));
        var d=result.putObject("derivation").put("strategy",VERSION).put("motionKind","ANIMATED").put("direction",w.direction())
            .put("sourceMotionSha256",previous.path("sha256").asText()).put("sourceRawSha256",sha)
            .put("rawReviewHashVersion",HASH_VERSION).put("rawReviewSha256",canonicalDigest(r,json));
        d.set("seedHashes",seeds);d.set("rawFrameHashes",r.path("reviewedFrameHashes"));d.set("rawProviderReview",report.path("rawEditReview"));
        r.put("inputSha256",sha).put("action",w.action()).put("direction",w.direction());
        r.set("seedHashes",seeds);r.set("learnedLessons",report.path("learnedLessons"));r.set("lessonsSha256",report.path("lessonsSha256"));
        StyledMotionReview.bind(r,seed,frames,json);
        if(!bound(result,r,seeds,w.direction(),json))throw invalid();
        return new Candidate(result,r);
    }
    static boolean bound(JsonNode result,JsonNode report,JsonNode seeds,String direction,JsonMapper json) {
        var d=result.path("derivation");var raw=d.path("rawProviderReview");
        return derived(result) && !result.has("rawEdit") && d.path("motionKind").asText().equals("ANIMATED")
            && d.path("direction").asText().equals(direction) && d.path("seedHashes").equals(seeds)
            && d.path("sourceMotionSha256").asText().matches("[a-f0-9]{64}")
            && !d.path("sourceMotionSha256").equals(result.path("sha256"))
            && d.path("sourceRawSha256").equals(result.path("sha256")) && result.path("sha256").equals(report.path("inputSha256"))
            && d.path("rawFrameHashes").equals(result.path("frameHashes")) && result.path("frameHashes").size()==9
            && result.path("frameHashes").equals(raw.path("reviewedFrameHashes"))
            && (d.has("rawReviewHashVersion")?HASH_VERSION.equals(d.path("rawReviewHashVersion").asText())
                && d.path("rawReviewSha256").asText().equals(canonicalDigest(raw,json)):
                d.path("rawReviewSha256").asText().equals(StyledAutoApproval.digest(raw,json)))
            && genuinePass(raw,report.path("action").asText(),false) && StyledMotionReview.boundPass(report)
            && report.path("referenceFrameSha256").equals(raw.path("referenceFrameSha256"));
    }
    static String canonicalDigest(JsonNode value,JsonMapper json) {return StyledSpriteCodec.sha(json.writeValueAsBytes(canonical(value,json)));}
    private static JsonNode canonical(JsonNode value,JsonMapper json) {
        if(value.isObject()) {var o=json.createObjectNode();for(String key:new TreeSet<>(value.propertyNames()))o.set(key,canonical(value.path(key),json));return o;}
        if(value.isArray()) {var a=json.createArrayNode();value.forEach(v->a.add(canonical(v,json)));return a;}
        return value;
    }
    /** Legacy JSONB reorders object keys. Rebind only against the original archived adoption,
     * exact raw QA, nine frames and current passing evidence; never rewrite a quality verdict. */
    static JsonNode normalizeLegacy(JsonNode result,JsonNode report,JsonNode seeds,String direction,JsonNode archive,JsonMapper json) {
        var d=result.path("derivation");var raw=d.path("rawProviderReview");
        if(!derived(result) || d.has("rawReviewHashVersion") || !archive.path("rawMotionAdoption").asBoolean()
            || !archive.at("/quality/rawEditReview").equals(raw) || !genuinePass(raw,report.path("action").asText())
            || !archive.at("/quality/seedHashes").equals(seeds)
            || !archive.at("/result/sha256").equals(d.path("sourceMotionSha256"))
            || !archive.at("/quality/inputSha256").equals(d.path("sourceMotionSha256"))
            || !archive.at("/result/rawEdit/sha256").equals(result.path("sha256"))
            || !archive.at("/quality/rawEditSha256").equals(result.path("sha256")))return null;
        var candidate=(ObjectNode)result.deepCopy();var proof=(ObjectNode)candidate.path("derivation");
        proof.put("legacyRawReviewSha256",d.path("rawReviewSha256").asText()).put("rawReviewHashVersion",HASH_VERSION)
            .put("rawReviewSha256",canonicalDigest(raw,json)).put("normalizationSourceSha256",canonicalDigest(archive,json));
        return bound(candidate,report,seeds,direction,json)?candidate:null;
    }
    private static AssetException invalid(){return new AssetException(409,"RAW_MOTION_SOURCE_CHANGED");}
}
