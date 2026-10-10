package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A bounded alternative to an unresolved clip, never a verdict or a training label. */
final class StyledMotionCandidate {
    static final String VERSION="unresolved-motion-candidate-v1";
    private StyledMotionCandidate() {}
    static boolean enabled(JsonNode policy) {return policy!=null && VERSION.equals(policy.path("motionCandidateVersion").asText());}
    static JsonNode plan(JsonNode report,String action,JsonMapper json) {
        if(report==null || report.path("passed").asBoolean() || !StyledMotionReview.unresolved(report))return null;
        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String key:List.of("rawEditReview","restoredReview"))if(report.has(key))reports.add(report.path(key));
        var issues=new TreeSet<String>();var frames=new TreeSet<Integer>();var properties=new TreeSet<String>();
        var preserve=new TreeSet<String>();
        try {
            for(var r:reports) {
                if(!StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
                    || !r.path("rulesSha256").asText().equals(StyledSpriteCodec.qualityRulesSha()))return null;
                int count=r.path("observationCount").asInt();
                if(count<1 || count>2 || r.has("consistencyReview")!=(count==2))return null;
                var observations=count==1?List.of(r.path("initialVision")):List.of(r.path("initialVision"),r.path("consistencyReview"));
                for(var observation:observations) {
                    StyledMotionReview.validate(observation,action);
                    if(!StyledMotionReview.property(observation,"referencePose").path("state").asText().equals("PASS"))return null;
                }
                for(var issue:r.path("issues")) {
                    if(!StyledMotionReview.PROPERTIES.containsValue(issue.asText())
                        && !Set.of("CANVAS_CLIPPING","DETACHED_PIXELS").contains(issue.asText()))return null;
                    issues.add(issue.asText());
                }
                // Pure UNKNOWN has no edit target, but does not cancel a separate concrete target.
                // Preserve that property; the one-candidate limit and full review still apply.
                for(String property:StyledMotionReview.PROPERTIES.keySet()) {
                    boolean disputed=false,concrete=false;
                    for(var observation:observations) {
                        var p=StyledMotionReview.property(observation,property);
                        if(!p.path("state").asText().equals("PASS"))disputed=true;
                        if(p.path("state").asText().equals("FAIL")) {
                            concrete=true;properties.add(property);issues.add(StyledMotionReview.PROPERTIES.get(property));
                            p.path("frames").forEach(f->frames.add(f.asInt()));
                        }
                    }
                    if(disputed && !concrete)preserve.add(property);
                }
            }
        } catch(AssetException invalid) {return null;}
        // Do not edit a property that has no concrete target in one of the original/restored reports.
        if(!Collections.disjoint(properties,preserve) || issues.isEmpty() || frames.isEmpty())return null;
        var plan=json.createObjectNode().put("version",VERSION).put("assessment","UNCONFIRMED_CANDIDATE_ONLY");
        plan.set("issues",json.valueToTree(issues));plan.set("frames",json.valueToTree(frames));plan.set("properties",json.valueToTree(properties));
        plan.set("preservedUncertainProperties",json.valueToTree(preserve));
        plan.put("note","Observation conflict, NOT a confirmed defect. Make one conservative alternative for "+properties+" only. Preserve the approved entry pose, anatomy, facing, style and unrelated features, including "+preserve+". Do not invent hidden features. The new candidate needs fresh full review.");
        return plan;
    }
}
