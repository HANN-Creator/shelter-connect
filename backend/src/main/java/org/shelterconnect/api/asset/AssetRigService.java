package org.shelterconnect.api.asset;

import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class AssetRigService {
    private final AssetStore store;private final AssetStorage storage;private final MotionHarness harness;
    public AssetRigService(AssetStore store,AssetStorage storage,MotionHarness harness) {
        this.store=store;this.storage=storage;this.harness=harness;
    }
    public Map<String,Object> preview(UUID subject,UUID dog,UUID id) {
        var job=store.rigForReview(subject,dog,id);
        String key=baseKey(job);var links=storage.sign(List.of(key));
        store.rigForReview(subject,dog,id);
        var result=new LinkedHashMap<String,Object>();
        result.put("jobId",id);result.put("expectedRevision",job.rigRevision());result.put("profile",job.rigProfile());
        result.put("baseUrl",Objects.requireNonNull(links.get(key)));result.put("reviewRequired",true);
        result.put("actionPlan",job.actionPlan());result.put("fitMethod","bounding-box-proposal");
        return result;
    }
    public AssetStore.Job confirm(UUID subject,UUID dog,UUID id,JsonNode body) {
        AssetInput.fields(body,"expectedRevision","profile");
        var revision=body.path("expectedRevision");var profile=body.path("profile");
        if(!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.asInt()<1 || !profile.isObject() || profile.toString().length()>24000) throw AssetException.invalid();
        var before=store.rigForConfirmation(subject,dog,id);
        if(before.rigRevision()!=revision.asInt()) throw new AssetException(409,"RIG_CHANGED");
        byte[] base=storage.asset(baseKey(before));
        // All selected local motions must fit before the immutable profile can be accepted.
        for(String name:before.actionPlan()) {
            var action=AssetAction.valueOf(name);
            if(MotionHarness.handles(action)) harness.render(action,base,profile);
        }
        return store.confirmRig(subject,dog,id,revision.asInt(),profile,sha256(base));
    }
    private static String baseKey(AssetStore.Job job) {
        return job.steps().stream().filter(s->s.action().equals("BASE") && s.status().equals("SUCCEEDED") && s.result()!=null)
            .map(s->s.result().path("key").asText()).findFirst().orElseThrow(()->new AssetException(409,"ASSET_NOT_READY"));
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
