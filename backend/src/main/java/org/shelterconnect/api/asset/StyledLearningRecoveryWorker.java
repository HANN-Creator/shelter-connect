package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.chat.AiProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StyledLearningRecoveryWorker {
    private final StyledLearningRecoveryStore store;private final StyledQualityAgent quality;
    private final AssetStorage storage;private final AssetProperties assets;private final AiProperties ai;private final JsonMapper json;
    public StyledLearningRecoveryWorker(StyledLearningRecoveryStore store,StyledQualityAgent quality,AssetStorage storage,AssetProperties assets,AiProperties ai,JsonMapper json) {
        this.store=store;this.quality=quality;this.storage=storage;this.assets=assets;this.ai=ai;this.json=json;
    }
    public void tick() {
        if(!assets.enabled || !ai.enabled())return;
        var w=store.claim();if(w==null)return;
        try {
            // Older combined verdicts discarded a good raw edit. Recover only its own hash-bound verdict.
            var raw=w.result().path("rawEdit");var rawReport=w.report().path("rawEditReview");
            if(rawReport.path("passed").asBoolean() && !raw.path("sha256").asText().equals(w.result().path("sha256").asText()) && raw.path("sha256").asText().equals(w.report().path("rawEditSha256").asText())
                && StyledSpriteCodec.qualityRulesSha().equals(rawReport.path("rulesSha256").asText())) {
                load(w,raw.path("key").asText(),raw.path("sha256").asText());store.evidence(w,raw,rawReport,false);
            }
            var learned=store.newLessons(w);
            if(!learned.isEmpty()){store.schedule(w,learned);return;}
            if(store.hasPositive(w)){store.waitForRule(w,"VALIDATING_OR_WAITING_FOR_RULE");return;}
            if(!w.action().equals("IDLE")){motionReference(w);return;}
            if(w.referenceReport()!=null){store.stop(w,"NEEDS_REVIEW","NO_ACCEPTED_POSITIVE_REFERENCE");return;}
            var seeds=new ArrayList<byte[]>();
            for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(load(w,w.seeds().at("/keys/"+d).asText(),w.seeds().at("/hashes/"+d).asText()));
            byte[] seed=seeds.get(List.of("south","north","west","east").indexOf(w.direction()));
            // This is a labeled learning reference, never a replacement for the failed animation or a manifest asset.
            var frames=Collections.nCopies(9,seed);byte[] reference=StyledSpriteCodec.rawSheet(frames);
            String hash=StyledSpriteCodec.sha(reference);store.startReference(w);
            if(hash.equals(w.result().path("sha256").asText())) {store.stop(w,"NEEDS_REVIEW","REFERENCE_EQUALS_FAILED_IMAGE");return;}
            var report=(tools.jackson.databind.node.ObjectNode)quality.review(w.policy().path("contract"),seeds,frames,"IDLE",w.direction()).deepCopy();
            report.put("evidenceKind","APPROVED_IDLE_REFERENCE");report.put("inputSha256",hash);
            if(!StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText()))throw new AssetException(409,"LEARNING_REFERENCE_REPORT_INVALID");
            String key=w.prefix()+"learning-references/"+w.label()+"-"+StyledSpriteCodec.qualityRulesSha()+".png";
            if(!store.authorized(w))return;
            storage.put(key,reference);
            store.evidence(w,json.valueToTree(Map.of("key",key,"sha256",hash,"evidenceKind","APPROVED_IDLE_REFERENCE")),report,true);
            if(store.hasPositive(w))store.waitForRule(w,"VALIDATING_OR_WAITING_FOR_RULE");
            else store.stop(w,"NEEDS_REVIEW","REFERENCE_FAILED_OR_CONFLICTING_LABEL");
        }catch(RuntimeException e){store.stop(w,"FAILED",e instanceof AssetException a?a.code:"REFERENCE_MODEL_OR_STORAGE_FAILED");}
    }
    private void motionReference(StyledLearningRecoveryStore.Work w) {
        if(w.referenceReport()!=null){store.waitForRule(w,"NO_APPROVED_MOTION_REFERENCE");return;}
        var reference=store.historical(w);
        if(reference==null){store.waitForRule(w,"NO_APPROVED_MOTION_REFERENCE");return;}
        store.startReference(w);var seeds=new ArrayList<byte[]>();
        for(String direction:StyledSpriteCodec.DIRECTIONS)seeds.add(loadReference(w,reference,reference.seeds().at("/keys/"+direction).asText(),reference.seeds().at("/hashes/"+direction).asText()));
        byte[] sheet=loadReference(w,reference,reference.result().path("key").asText(),reference.result().path("sha256").asText());
        var report=(tools.jackson.databind.node.ObjectNode)quality.review(w.policy().path("contract"),seeds,StyledSpriteCodec.frames(sheet),w.action(),w.direction()).deepCopy();
        report.put("evidenceKind","REVALIDATED_HISTORICAL_MOTION").put("sourceExampleId",reference.id().toString())
            .put("inputSha256",StyledSpriteCodec.sha(sheet));
        store.historicalEvidence(w,reference,report);
        store.waitForRule(w,report.path("passed").asBoolean()?"VALIDATING_OR_WAITING_FOR_RULE":"NO_APPROVED_MOTION_REFERENCE");
    }
    private byte[] loadReference(StyledLearningRecoveryStore.Work w,StyledLearningRecoveryStore.Reference reference,String key,String sha) {
        if(!key.startsWith(reference.prefix()) || !sha.matches("[a-f0-9]{64}") || !store.referenceAllowed(w,reference))throw new AssetException(409,"LEARNING_EVIDENCE_CHANGED");
        byte[] bytes=storage.asset(key);if(!StyledSpriteCodec.sha(bytes).equals(sha))throw new AssetException(409,"LEARNING_EVIDENCE_CHANGED");return bytes;
    }
    private byte[] load(StyledLearningRecoveryStore.Work w,String key,String sha) {
        if(!key.startsWith(w.prefix()) || !sha.matches("[a-f0-9]{64}"))throw new AssetException(409,"LEARNING_EVIDENCE_CHANGED");
        if(!store.authorized(w))throw new AssetException(409,"LEARNING_RECOVERY_STALE");
        byte[] data=storage.asset(key);if(!StyledSpriteCodec.sha(data).equals(sha))throw new AssetException(409,"LEARNING_EVIDENCE_CHANGED");return data;
    }
}
