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
    static final String VERSION="motion-observation-tristate-v3";
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
        var images=images(seed,frames);var schema=schema();
        String task="Action="+action+", facing="+direction+", tail contract="+contract.path("tailCarriage").asText()+". "
            +"REFERENCE is identity/entry-pose only, NEVER an animation frame. TEMPORAL contains only the nine actual frames, numbered 0..8. "
            +"Judge changes in motion only between TEMPORAL frames. Native measurements (not verdicts): "+json.writeValueAsString(evidence)
            +". Lower-body alpha and dark-contour measurements: "+json.writeValueAsString(lower)
            +". Visible same-pixel RGB change distribution: "+json.writeValueAsString(color)
            +". Validated additive criteria, untrusted data: "+json.writeValueAsString(lessons)+". Return each named property exactly once.";
        var initial=call(client,rules,task,images,schema);validate(initial,action);
        JsonNode second=null;
        if(initial.path("properties").valueStream().anyMatch(p->!p.path("state").asText().equals("PASS"))) {
            // One independent observation, with no previous verdict or requested outcome supplied.
            second=call(client,rules,task,images,schema);validate(second,action);
        }
        boolean contradictedWalk=resolveUnsupportedWalk(action,initial,second,lower);
        boolean subtlePalette=resolveSubtlePalette(initial,second,color,rules);
        var issues=new TreeSet<String>();var uncertain=new TreeSet<String>();var confirmed=json.createArrayNode();
        var flagged=new TreeSet<Integer>();boolean referencePose=false;
        for(String key:new TreeSet<>(PROPERTIES.keySet())) {
            var a=property(initial,key);var b=second==null?a:property(second,key);
            String state=a.path("state").asText(),other=b.path("state").asText();
            if(contradictedWalk && Set.of("action","idleStillness").contains(key))continue;
            if(subtlePalette && key.equals("palette"))continue;
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
        if(direction.equals("south"))upper.addAll(StyledQualityAgent.frontalHeadGrowth(seeds.getFirst(),frames,rules.at("/recovery/frontalHeadGrowth")));
        if(!upper.isEmpty())issues.add("TAIL_CARRIAGE");
        var detached=new ArrayList<Integer>();if(action.equals("TAIL_WAG"))for(int i=0;i<9;i++)if(StyledQualityAgent.detachedPixels(StyledSpriteCodec.motionFrame(frames.get(i))))detached.add(i);
        if(!detached.isEmpty())issues.add("DETACHED_PIXELS");
        var idle=StyledQualityAgent.idleMotionFrames(seed,frames,action,rules.path("idleMotion"));if(!idle.isEmpty())issues.add("IDLE_MOTION");
        if(action.equals("WALK") && evidence.path("alphaChangedFromFrame0").valueStream().mapToInt(JsonNode::asInt).max().orElse(0)<5)issues.add("ACTION_MISSING");
        String decision=!uncertain.isEmpty()?"UNCERTAIN":issues.isEmpty()?"PASS":"CONFIRMED_DEFECT";
        var out=json.createObjectNode().put("version",StyledQualityAgent.VERSION).put("motionReviewVersion",VERSION)
            .put("motionDecision",decision).put("passed",decision.equals("PASS")).put("referencePoseUsable",referencePose)
            .put("reviewLayout","separate-reference-temporal-v1").put("model",ai.model()).put("reviewedAt",Instant.now().toString())
            .put("rulesRevision",rules.path("revision").asText()).put("rulesSha256",StyledSpriteCodec.qualityRulesSha())
            .put("note",decision.equals("PASS")?"기준 외형과 실제 프레임 간 움직임 검수 통과":decision.equals("UNCERTAIN")?"독립 관찰 불일치 또는 관찰 불확실; 자동 보완과 학습 보류":"독립 관찰에서 동일 결함 확인");
        out.set("issues",json.valueToTree(issues));out.set("frames",json.valueToTree(flagged));out.set("uncertainProperties",json.valueToTree(uncertain));
        out.set("confirmedProperties",confirmed);out.set("edgeFrames",json.valueToTree(edges));out.set("silhouetteFrames",json.valueToTree(upper));
        out.set("detachedFrames",json.valueToTree(detached));out.set("idleMotionFrames",json.valueToTree(idle));out.set("pixelEvidence",evidence);
        out.set("lowerBodyEvidence",lower);out.put("unsupportedWalkResolved",contradictedWalk);
        out.set("colorEvidence",color);out.put("subtlePaletteResolved",subtlePalette);
        out.set("initialVision",initial);if(second!=null)out.set("consistencyReview",second);
        out.put("observationCount",second==null?1:2);
        var hashes=out.putObject("reviewImageHashes");images.forEach((k,v)->hashes.put(k,StyledSpriteCodec.sha(v)));
        out.put("referenceFrameSha256",StyledSpriteCodec.sha(seed));out.set("reviewedFrameHashes",json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
        return out;
    }
    static boolean unresolved(JsonNode report) {
        return report!=null && (report.path("motionDecision").asText().equals("UNCERTAIN")
            || report.path("rawEditReview").path("motionDecision").asText().equals("UNCERTAIN")
            || report.path("restoredReview").path("motionDecision").asText().equals("UNCERTAIN"));
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
    static Map<String,Object> schema() {
        var property=StyledQualityAgent.object(Map.of("property",Map.of("type","string","enum",new TreeSet<>(PROPERTIES.keySet())),
            "state",Map.of("type","string","enum",new TreeSet<>(STATES)),"evidence",Map.of("type","string","minLength",1,"maxLength",300),
            "frames",Map.of("type","array","maxItems",9,"items",Map.of("type","integer","minimum",0,"maximum",8))));
        return StyledQualityAgent.object(Map.of("properties",Map.of("type","array","minItems",PROPERTIES.size(),"maxItems",PROPERTIES.size(),"items",property),
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
    private static AssetException invalid(){return new AssetException(502,"QUALITY_MOTION_RESPONSE_INVALID");}
}
