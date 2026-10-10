package org.shelterconnect.api.asset;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;
import java.util.List;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Separates reference identity from temporal motion; disagreement is not a training label. */
final class StyledMotionReview {
    static final String VERSION="motion-observation-tristate-v4";
    static final String RESPONSE_VERSION="named-motion-response-v1";
    static final Set<String> MOTIONS=Set.of("STILL","BREATH_BLINK","WALK_RUN","TAIL_MOVEMENT","OTHER_MOVEMENT","UNCERTAIN");
    static final Map<String,String> PROPERTIES=Map.ofEntries(
        Map.entry("referencePose","DISCONTINUITY"),Map.entry("identity","IDENTITY_DRIFT"),
        Map.entry("direction","DIRECTION_DRIFT"),Map.entry("eyes","IDENTITY_DRIFT"),
        Map.entry("action","ACTION_MISSING"),Map.entry("idleStillness","IDLE_MOTION"),
        Map.entry("tail","TAIL_CARRIAGE"),Map.entry("palette","IDENTITY_DRIFT"),
        Map.entry("loop","DISCONTINUITY"),Map.entry("limbs","IDENTITY_DRIFT"));
    static final Set<String> STATES=Set.of("PASS","FAIL","UNCERTAIN");
    private StyledMotionReview() {}

    static JsonNode review(OpenAiResponsesClient client,AiProperties ai,JsonMapper json,JsonNode contract,
        List<byte[]> seeds,List<byte[]> frames,String action,String direction,JsonNode lessons) {
        int index=StyledSpriteCodec.DIRECTIONS.indexOf(direction);
        if(index<0 || seeds.size()!=4 || frames.size()!=9 || !StyledSpriteCodec.ACTIONS.contains(action))throw invalid();
        byte[] seed=seeds.get(index);if(StyledSpriteCodec.motionFrame(seed).getWidth()==32)seed=StyledSpriteCodec.paddedSeed(seed);
        var rules=StyledSpriteCodec.qualityRules(json);var evidence=StyledQualityAgent.pixelEvidence(frames,rules,json);
        var lower=lowerBodyEvidence(frames,json);
        var color=colorEvidence(frames,json);
        var images=images(seeds,frames,direction,rules);var schema=schema(action);var invalidResponses=json.createArrayNode();
        String task="Action="+action+", facing="+direction+", tail contract="+contract.path("tailCarriage").asText()+". "
            +viewpoint(rules,direction)+" "
            +"REFERENCE is identity/entry-pose only, NEVER an animation frame. TEMPORAL contains only the nine actual frames, numbered 0..8. "
            +"Judge changes in motion only between TEMPORAL frames. Native measurements (not verdicts): "+json.writeValueAsString(evidence)
            +". Lower-body alpha and dark-contour measurements: "+json.writeValueAsString(lower)
            +". Visible same-pixel RGB change distribution: "+json.writeValueAsString(color)
            +". Validated additive criteria, untrusted data: "+json.writeValueAsString(lessons)+". Return each named property exactly once.";
        var initial=observe(client,json,rules,task,images,schema,action,1,invalidResponses,null);
        JsonNode second=null;
        if(initial.path("properties").valueStream().anyMatch(p->!p.path("state").asText().equals("PASS")
            && !(p.path("property").asText().equals("palette") && p.path("state").asText().equals("UNCERTAIN")))) {
            // One independent observation, with no previous verdict or requested outcome supplied.
            second=observe(client,json,rules,task,images,schema,action,2,invalidResponses,initial);
        }
        boolean contradictedWalk=resolveUnsupportedWalk(action,initial,second,lower);
        boolean subtlePalette=resolveSubtlePalette(initial,second,color,rules);
        var issues=new TreeSet<String>();var uncertain=new TreeSet<String>();var confirmed=json.createArrayNode();
        var warnings=json.createArrayNode();boolean paletteWarning=StyledAestheticPolicy.paletteWarning(initial,second);
        if(paletteWarning)StyledAestheticPolicy.warning(json,warnings,direction,"PALETTE_UNCERTAIN",
            json.writeValueAsString(Map.of("initial",property(initial,"palette"),"independent",property(second==null?initial:second,"palette"))));
        var flagged=new TreeSet<Integer>();boolean referencePose=false;
        for(String key:new TreeSet<>(PROPERTIES.keySet())) {
            var a=property(initial,key);var b=second==null?a:property(second,key);
            String state=a.path("state").asText(),other=b.path("state").asText();
            if(contradictedWalk && Set.of("action","idleStillness").contains(key))continue;
            if((subtlePalette || paletteWarning) && key.equals("palette"))continue;
            if(action.equals("IDLE") && Set.of("action","idleStillness").contains(key)
                && stationaryLowerBody(lower) && ((state.equals("FAIL") && initial.path("observedMotion").asText().equals("WALK_RUN"))
                    || (second!=null && other.equals("FAIL") && second.path("observedMotion").asText().equals("WALK_RUN")))) {
                uncertain.add(key);continue;
            }
            if(!state.equals(other) || state.equals("UNCERTAIN"))uncertain.add(key);
            else if(state.equals("FAIL")) {
                issues.add(PROPERTIES.get(key));confirmed.add(key);
                a.path("frames").forEach(f->flagged.add(f.asInt()));b.path("frames").forEach(f->flagged.add(f.asInt()));
            } else if(key.equals("referencePose"))referencePose=true;
        }
        var edges=new ArrayList<Integer>();for(int i=0;i<9;i++)if(StyledQualityAgent.touchesEdge(StyledSpriteCodec.motionFrame(frames.get(i))))edges.add(i);
        if(!edges.isEmpty())issues.add("CANVAS_CLIPPING");
        var upper=new TreeSet<>(StyledQualityAgent.frontalTailFrames(seeds.getFirst(),frames,action,direction,contract.path("tailCarriage").asText(),rules.path("frontalLowTail")));
        var headGrowth=direction.equals("south")?StyledQualityAgent.frontalHeadGrowth(seeds.getFirst(),frames,rules.at("/recovery/frontalHeadGrowth")):List.<Integer>of();
        upper.addAll(headGrowth);
        if(!upper.isEmpty())issues.add("TAIL_CARRIAGE");
        var detached=new ArrayList<Integer>();if(action.equals("TAIL_WAG"))for(int i=0;i<9;i++)if(StyledQualityAgent.detachedPixels(StyledSpriteCodec.motionFrame(frames.get(i))))detached.add(i);
        if(!detached.isEmpty())issues.add("DETACHED_PIXELS");
        var idle=StyledQualityAgent.idleMotionFrames(seed,frames,action,rules.path("idleMotion"));if(!idle.isEmpty())issues.add("IDLE_MOTION");
        if(action.equals("WALK") && evidence.path("alphaChangedFromFrame0").valueStream().mapToInt(JsonNode::asInt).max().orElse(0)<5)issues.add("ACTION_MISSING");
        boolean headDefect=resolveFrontalAppendage(action,direction,initial,second,lower,headGrowth);
        if(headDefect){uncertain.remove("tail");if(!confirmed.valueStream().anyMatch(n->n.asText().equals("tail")))confirmed.add("tail");flagged.addAll(upper);}
        String decision=!uncertain.isEmpty()?"UNCERTAIN":issues.isEmpty()?"PASS":"CONFIRMED_DEFECT";
        var out=json.createObjectNode().put("version",StyledQualityAgent.VERSION).put("motionReviewVersion",VERSION)
            .put("motionDecision",decision).put("passed",decision.equals("PASS")).put("referencePoseUsable",referencePose)
            .put("reviewLayout","separate-reference-temporal-v1").put("model",ai.model()).put("reviewedAt",Instant.now().toString())
            .put("rulesRevision",rules.path("revision").asText()).put("rulesSha256",StyledSpriteCodec.qualityRulesSha())
            .put("note",decision.equals("PASS")?"기준 외형과 실제 프레임 간 움직임 검수 통과":decision.equals("UNCERTAIN")?"독립 관찰 불일치 또는 관찰 불확실; 자동 보완과 학습 보류":"독립 관찰에서 동일 결함 확인");
        out.put("aestheticPolicy",StyledAestheticPolicy.VERSION);out.set("qualityWarnings",warnings);
        out.put("viewpointProtocolVersion",rules.at("/recovery/motionViewpoint/version").asText());
        out.put("requestedView",rules.at("/recovery/motionViewpoint/labels/"+direction).asText());
        out.set("issues",json.valueToTree(issues));out.set("frames",json.valueToTree(flagged));out.set("uncertainProperties",json.valueToTree(uncertain));
        out.set("confirmedProperties",confirmed);out.set("edgeFrames",json.valueToTree(edges));out.set("silhouetteFrames",json.valueToTree(upper));
        out.set("detachedFrames",json.valueToTree(detached));out.set("idleMotionFrames",json.valueToTree(idle));out.set("pixelEvidence",evidence);
        out.put("frontalAppendageConfirmed",headDefect);out.set("lowerBodyEvidence",lower);out.put("unsupportedWalkResolved",contradictedWalk);
        out.set("colorEvidence",color);out.put("subtlePaletteResolved",subtlePalette);
        out.set("initialVision",initial);if(second!=null)out.set("consistencyReview",second);
        out.put("observationCount",second==null?1:2);
        out.put("responseProtocolVersion",RESPONSE_VERSION);out.set("invalidResponses",invalidResponses);
        var hashes=out.putObject("reviewImageHashes");images.forEach((k,v)->hashes.put(k,StyledSpriteCodec.sha(v)));
        out.put("referenceFrameSha256",StyledSpriteCodec.sha(seed));out.set("reviewedFrameHashes",json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
        return out;
    }
    static boolean unresolved(JsonNode report) {
        return report!=null && (report.path("motionDecision").asText().equals("UNCERTAIN")
            || report.path("rawEditReview").path("motionDecision").asText().equals("UNCERTAIN")
            || report.path("restoredReview").path("motionDecision").asText().equals("UNCERTAIN"));
    }
    /** A confirmed tail defect can be repaired even if one observer also labels those SAME
     * frames as identity drift. Preserve UNCERTAIN for approval/learning; never infer missing anatomy.
     */
    static boolean confirmedTailRepair(JsonNode report) {
        if(!unresolved(report))return false;
        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String key:List.of("rawEditReview","restoredReview"))if(report.has(key))reports.add(report.path(key));
        for(var r:reports) {
            if(!VERSION.equals(r.path("motionReviewVersion").asText()) || r.path("passed").asBoolean()
                || r.path("observationCount").asInt()!=2 || !r.path("uncertainProperties").isArray()
                || r.path("uncertainProperties").valueStream().anyMatch(n->!n.asText().equals("identity"))
                || !r.path("confirmedProperties").valueStream().anyMatch(n->n.asText().equals("tail")))return false;
            var a=r.path("initialVision");var b=r.path("consistencyReview");
            try {
                var ta=property(a,"tail");var tb=property(b,"tail");
                if(!ta.path("state").asText().equals("FAIL") || !tb.path("state").asText().equals("FAIL"))return false;
                var shared=new HashSet<Integer>();ta.path("frames").forEach(f->shared.add(f.asInt()));
                var other=new HashSet<Integer>();tb.path("frames").forEach(f->other.add(f.asInt()));shared.retainAll(other);
                if(shared.isEmpty())return false;
                if(r.path("uncertainProperties").valueStream().anyMatch(n->n.asText().equals("identity"))) {
                    var ia=property(a,"identity");var ib=property(b,"identity");
                    var states=Set.of(ia.path("state").asText(),ib.path("state").asText());
                    if(!states.equals(Set.of("PASS","FAIL")) && !states.equals(Set.of("PASS","UNCERTAIN")))return false;
                    // The identity label may be tentative even when both observations identify
                    // the same concrete tail defect. Repair that defect, never promote this report.
                    var concern=ia.path("state").asText().equals("PASS")?ib:ia;
                    if(!concern.path("frames").isArray() || concern.path("frames").isEmpty()
                        || concern.path("frames").valueStream().anyMatch(f->!f.isIntegralNumber() || f.asInt()<0 || f.asInt()>8 || !shared.contains(f.asInt())))return false;
                }
            } catch(AssetException|IllegalArgumentException missing){return false;}
        }
        return true;
    }
    /** Repair the independently confirmed color defect; disagreement over its identity label remains unlearned. */
    static boolean confirmedPaletteRepair(JsonNode report) {
        if(!unresolved(report))return false;
        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String key:List.of("rawEditReview","restoredReview"))if(report.has(key))reports.add(report.path(key));
        for(var r:reports) {
            if(!VERSION.equals(r.path("motionReviewVersion").asText()) || r.path("passed").asBoolean() || r.path("observationCount").asInt()!=2
                || !r.path("uncertainProperties").toString().equals("[\"identity\"]")
                || !r.path("confirmedProperties").toString().equals("[\"palette\"]"))return false;
            try {
                var a=r.path("initialVision");var b=r.path("consistencyReview");var paletteFrames=new HashSet<Integer>();var identityFrames=new HashSet<Integer>();
                var identityStates=new HashSet<String>();
                for(var o:List.of(a,b))for(String key:PROPERTIES.keySet()) {
                    var p=property(o,key);String state=p.path("state").asText();
                    if(key.equals("palette")) {if(!state.equals("FAIL") || p.path("frames").isEmpty())return false;p.path("frames").forEach(f->paletteFrames.add(f.asInt()));}
                    else if(key.equals("identity")){identityStates.add(state);if(state.equals("FAIL"))p.path("frames").forEach(f->identityFrames.add(f.asInt()));}
                    else if(!state.equals("PASS"))return false;
                }
                if(!identityStates.equals(Set.of("PASS","FAIL")) || identityFrames.isEmpty() || !paletteFrames.containsAll(identityFrames))return false;
            }catch(AssetException invalid){return false;}
        }return true;
    }
    static void bind(ObjectNode report,byte[] seed,List<byte[]> frames,JsonMapper json) {
        report.put("recoveryVersion",StyledRecovery.VERSION).put("motionSeedSha256",StyledSpriteCodec.sha(seed));
        report.set("frameHashes",json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
        var first=StyledSpriteCodec.motionFrame(frames.getFirst());var origin=StyledSpriteCodec.motionFrame(seed);boolean equal=true;
        for(int y=0;y<40;y++)for(int x=0;x<40;x++)if(first.getRGB(x,y)!=origin.getRGB(x,y))equal=false;
        // Provenance diagnostic, not a demand that an image generator reproduce every RGBA byte.
        report.put("firstFrameUnchanged",equal);
    }
    static boolean boundPass(JsonNode report) {
        return VERSION.equals(report.path("motionReviewVersion").asText()) && report.path("motionDecision").asText().equals("PASS")
            && report.path("referencePoseUsable").asBoolean() && report.path("referenceFrameSha256").equals(report.path("motionSeedSha256"))
            && report.path("reviewedFrameHashes").equals(report.path("frameHashes")) && report.path("reviewedFrameHashes").size()==9;
    }
    private static JsonNode call(OpenAiResponsesClient client,JsonNode rules,String task,Map<String,byte[]> images,Map<String,Object> schema) {
        try{return client.structuredImagesWithReasoning(rules.at("/recovery/motionObservation").asText(),task,images,schema,"medium");}
        catch(AiFailure e){throw new AssetException(502,"QUALITY_"+e.code());}
    }
    private static JsonNode observe(OpenAiResponsesClient client,JsonMapper json,JsonNode rules,String task,Map<String,byte[]> images,
        Map<String,Object> schema,String action,int ordinal,tools.jackson.databind.node.ArrayNode failures,JsonNode validFirst) {
        // A format retry is not a new quality vote. Keep valid observations, including FAIL/UNCERTAIN.
        for(int attempt=0;attempt<2;attempt++) {
            var raw=call(client,rules,task,images,schema);
            try {var normalized=normalize(raw,json);validate(normalized,action);return normalized;}
            catch(AssetException e) {
                var failure=failures.addObject().put("observation",ordinal).put("attempt",attempt+1).put("code",e.code);
                failure.set("response",raw);
            }
        }
        var diagnostic=json.createObjectNode().put("status","RESPONSE_INVALID").put("responseProtocolVersion",RESPONSE_VERSION)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("action",action);
        diagnostic.set("invalidResponses",failures.deepCopy());if(validFirst!=null)diagnostic.set("initialVision",validFirst);
        var hashes=diagnostic.putObject("reviewImageHashes");images.forEach((k,v)->hashes.put(k,StyledSpriteCodec.sha(v)));
        throw new AssetException(502,"QUALITY_MOTION_RESPONSE_INVALID",diagnostic);
    }
    static JsonNode normalize(JsonNode raw,JsonMapper json) {
        // Canonical stored reports remain compatible with historical receipts and regression fixtures.
        if(!raw.path("properties").isObject())return raw;
        if(raw.path("properties").size()!=PROPERTIES.size())throw invalid();
        var out=json.createObjectNode();out.set("observedMotion",raw.path("observedMotion"));var list=out.putArray("properties");
        for(String key:new TreeSet<>(PROPERTIES.keySet())) {
            var p=raw.path("properties").path(key);if(!p.isObject())throw invalid();
            var copy=(ObjectNode)p.deepCopy();copy.put("property",key);list.add(copy);
        }return out;
    }
    private static Map<String,Object> propertySchema(List<String> states,int minimumFrames) {
        return StyledQualityAgent.object(Map.of("state",Map.of("type","string","enum",states),
            "evidence",Map.of("type","string","minLength",1,"maxLength",300),
            "frames",Map.of("type","array","minItems",minimumFrames,"maxItems",9,"items",Map.of("type","integer","minimum",0,"maximum",8))));
    }
    static Map<String,Object> schema(String action) {
        var named=new TreeMap<String,Object>();
        for(String key:PROPERTIES.keySet())named.put(key,!action.equals("IDLE") && key.equals("idleStillness")?
            propertySchema(List.of("PASS"),0):Map.of("anyOf",List.of(propertySchema(List.of("PASS","UNCERTAIN"),0),propertySchema(List.of("FAIL"),1))));
        return StyledQualityAgent.object(Map.of("properties",StyledQualityAgent.object(named),
            "observedMotion",Map.of("type","string","enum",new TreeSet<>(MOTIONS))));
    }
    static JsonNode property(JsonNode r,String key){return r.path("properties").valueStream().filter(p->p.path("property").asText().equals(key)).findFirst().orElseThrow(StyledMotionReview::invalid);}
    static void validate(JsonNode r,String action) {
        if(!MOTIONS.contains(r.path("observedMotion").asText()) || !r.path("properties").isArray() || r.path("properties").size()!=PROPERTIES.size())throw invalid();var seen=new HashSet<String>();
        for(var p:r.path("properties")) {
            String key=p.path("property").asText(),state=p.path("state").asText();
            if(!PROPERTIES.containsKey(key) || !seen.add(key) || !STATES.contains(state) || !p.path("evidence").isString()
                || p.path("evidence").asText().isBlank() || p.path("evidence").asText().length()>300 || !p.path("frames").isArray() || p.path("frames").size()>9)throw invalid();
            for(var f:p.path("frames"))if(!f.isIntegralNumber() || f.asInt()<0 || f.asInt()>8)throw invalid();
            if(state.equals("FAIL") && p.path("frames").isEmpty())throw invalid();
            if(!action.equals("IDLE") && key.equals("idleStillness") && !state.equals("PASS"))throw invalid();
        }
    }
    /** A narrowly contradicted walking claim may be resolved, never tail/face/palette or genuine movement. */
    static boolean resolveUnsupportedWalk(String action,JsonNode first,JsonNode second,JsonNode lower) {
        return action.equals("IDLE") && second!=null && first.path("observedMotion").asText().equals("WALK_RUN")
            && Set.of("STILL","BREATH_BLINK").contains(second.path("observedMotion").asText())
            && stationaryLowerBody(lower)
            && second.path("properties").valueStream().allMatch(p->p.path("state").asText().equals("PASS"))
            && first.path("properties").valueStream().allMatch(p->p.path("state").asText().equals("PASS")
                || (Set.of("action","idleStillness").contains(p.path("property").asText()) && p.path("state").asText().equals("FAIL")));
    }
    /** Resolve toward a defect only: two motion observations plus new central-head pixels.
     * UNKNOWN photographic carriage cannot waive an invented moving head appendage.
     * Any other uncertainty, rear/side view, ear-only growth or normal breathing remains held.
     */
    static boolean resolveFrontalAppendage(String action,String direction,JsonNode a,JsonNode b,JsonNode lower,Collection<Integer> growth) {
        if(!action.equals("IDLE") || !direction.equals("south") || b==null || growth.isEmpty() || !stationaryLowerBody(lower))return false;
        boolean failedTail=false;
        for(var observation:List.of(a,b)) {
            if(!observation.path("observedMotion").asText().equals("TAIL_MOVEMENT"))return false;
            for(String key:PROPERTIES.keySet()) {
                String state=property(observation,key).path("state").asText();
                if(key.equals("tail")){if(!Set.of("FAIL","UNCERTAIN").contains(state))return false;failedTail|=state.equals("FAIL");}
                else if(Set.of("action","idleStillness","loop").contains(key)){if(!state.equals("FAIL"))return false;}
                else if(!state.equals("PASS"))return false;
            }
        }
        return failedTail;
    }
    private static boolean stationaryLowerBody(JsonNode lower) {
        return lower.path("alphaChanged").size()==9 && lower.path("darkContourChanged").size()==9
            && lower.path("alphaChanged").valueStream().allMatch(n->n.asInt()==0)
            && lower.path("darkContourChanged").valueStream().allMatch(n->n.asInt()<=1);
    }
    static boolean resolveSubtlePalette(JsonNode first,JsonNode second,JsonNode color,JsonNode rules) {
        return second!=null && property(first,"palette").path("state").asText().equals("FAIL")
            && first.path("properties").valueStream().allMatch(p->p.path("property").asText().equals("palette") || p.path("state").asText().equals("PASS"))
            && second.path("properties").valueStream().allMatch(p->p.path("state").asText().equals("PASS"))
            && color.path("pairs").size()==18 && color.path("pairs").valueStream().allMatch(p->p.path("median").asInt()==0
                && p.path("p90").asInt()<=rules.at("/pixelEvidence/subtleRgbChannelDelta").asInt()
                && p.path("p95").asInt()<=rules.at("/idleConsistencyReview/maximumRgbDelta").asInt());
    }
    static JsonNode colorEvidence(List<byte[]> frames,JsonMapper json) {
        var decoded=frames.stream().map(StyledSpriteCodec::motionFrame).toList();var out=json.createObjectNode().put("version","visible-rgb-distribution-v1");
        var pairs=out.putArray("pairs");
        for(int i=0;i<9;i++)for(int reference:new int[]{0,(i+8)%9}) {
            var values=new ArrayList<Integer>();var f=decoded.get(i);var base=decoded.get(reference);
            for(int y=0;y<40;y++)for(int x=0;x<40;x++){int a=f.getRGB(x,y),b=base.getRGB(x,y);
                if((a>>>24)!=0 && (b>>>24)!=0)values.add(Math.max(Math.abs((a>>16&255)-(b>>16&255)),Math.max(Math.abs((a>>8&255)-(b>>8&255)),Math.abs((a&255)-(b&255)))));
            }
            if(values.isEmpty())throw invalid();Collections.sort(values);
            pairs.addObject().put("frame",i).put("reference",reference).put("median",values.get((values.size()-1)/2))
                .put("p90",values.get((values.size()-1)*90/100)).put("p95",values.get((values.size()-1)*95/100));
        }return out;
    }
    static JsonNode lowerBodyEvidence(List<byte[]> frames,JsonMapper json) {
        var first=StyledSpriteCodec.motionFrame(frames.getFirst());int top=40,bottom=-1;
        for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((first.getRGB(x,y)>>>24)!=0){top=Math.min(top,y);bottom=Math.max(bottom,y);}
        if(bottom<0)throw invalid();int start=top+(bottom+1-top)*2/3;
        var alpha=new ArrayList<Integer>();var dark=new ArrayList<Integer>();
        for(var bytes:frames){var f=StyledSpriteCodec.motionFrame(bytes);int a=0,d=0;
            for(int y=start;y<40;y++)for(int x=0;x<40;x++){
                int old=first.getRGB(x,y),now=f.getRGB(x,y);if((old>>>24)!=(now>>>24))a++;
                if(dark(old)!=dark(now))d++;
            }alpha.add(a);dark.add(d);
        }
        var r=json.createObjectNode().put("version","lower-third-contour-v1").put("startRow",start).put("darkChannelCeilingExclusive",100);
        r.set("alphaChanged",json.valueToTree(alpha));r.set("darkContourChanged",json.valueToTree(dark));return r;
    }
    private static boolean dark(int p){return (p>>>24)!=0 && (p>>16&255)<100 && (p>>8&255)<100 && (p&255)<100;}
    static Map<String,byte[]> images(byte[] seed,List<byte[]> frames) {
        var reference=new BufferedImage(480,264,BufferedImage.TYPE_INT_RGB);var g=reference.createGraphics();StyledRecoveryReview.paint(g,480,264);
        g.drawString("REFERENCE: approved seed",8,20);g.drawString("Actual entry: frame 0",248,20);
        g.drawImage(StyledSpriteCodec.motionFrame(seed),0,24,240,240,null);g.drawImage(StyledSpriteCodec.motionFrame(frames.getFirst()),240,24,240,240,null);g.dispose();
        var temporal=new BufferedImage(720,792,BufferedImage.TYPE_INT_RGB);g=temporal.createGraphics();StyledRecoveryReview.paint(g,720,792);
        for(int i=0;i<9;i++){int x=i%3*240,y=i/3*264;g.drawString("ACTUAL FRAME "+i,x+8,y+20);g.drawImage(StyledSpriteCodec.motionFrame(frames.get(i)),x,y+24,240,240,null);}g.dispose();
        var images=new LinkedHashMap<String,byte[]>();images.put("REFERENCE only, never part of the timeline",StyledSpriteCodec.png(reference));
        images.put("TEMPORAL actual frames 0 through 8 only",StyledSpriteCodec.png(temporal));return images;
    }
    static String viewpoint(JsonNode rules,String direction) {
        String label=rules.at("/recovery/motionViewpoint/labels/"+direction).asText();
        if(!StyledSpriteCodec.DIRECTIONS.contains(direction) || label.isBlank())throw invalid();
        return "Requested camera view: "+label+". "+rules.at("/recovery/motionViewpoint/instruction").asText();
    }
    /** Static anatomical context separates the curled rump tail from frontal eyes/muzzle.
     * The requested labels are requirements, never evidence that actual pixels face correctly. */
    static Map<String,byte[]> images(List<byte[]> seeds,List<byte[]> frames,String direction,JsonNode rules) {
        int index=StyledSpriteCodec.DIRECTIONS.indexOf(direction);
        if(index<0 || seeds.size()!=4 || frames.size()!=9)throw invalid();
        var padded=seeds.stream().map(s->StyledSpriteCodec.motionFrame(s).getWidth()==32?StyledSpriteCodec.paddedSeed(s):s).toList();
        var key=new BufferedImage(960,264,BufferedImage.TYPE_INT_RGB);var g=key.createGraphics();StyledRecoveryReview.paint(g,960,264);
        for(int i=0;i<4;i++) {
            String label=rules.at("/recovery/motionViewpoint/labels/"+StyledSpriteCodec.DIRECTIONS.get(i)).asText();
            g.drawString(label+" reference",i*240+8,20);
            g.drawImage(StyledSpriteCodec.motionFrame(padded.get(i)),i*240,24,240,240,null);
        }g.dispose();
        var result=new LinkedHashMap<String,byte[]>();
        result.put("VIEW KEY: four approved static camera views, NOT motion frames",StyledSpriteCodec.png(key));
        result.putAll(images(padded.get(index),frames));return result;
    }
    private static AssetException invalid(){return new AssetException(502,"QUALITY_MOTION_RESPONSE_INVALID");}
}
