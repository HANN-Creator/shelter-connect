package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Observe tail anatomy separately from the general likeness verdict. Unknown photo anatomy is not a short-tail exception. */
final class StyledSeedTailEvidence {
    static final String VERSION="photo-tail-evidence-v1";
    static final List<String> SIDES=List.of("west","east");
    static final Set<String> PHOTO=Set.of("VISIBLE_EXTENDED","VISIBLE_NATURALLY_SHORT","OBSCURED","UNCERTAIN");
    static final Set<String> SPRITE=Set.of("COMPLETE_CONNECTED","SHORT_STUB","NOT_DISCERNIBLE","DETACHED_OR_CROPPED","UNCERTAIN");
    private StyledSeedTailEvidence(){}
    static Map<String,Object> schema(){
        var view=new LinkedHashMap<String,Object>();
        view.put("direction",Map.of("type","string","enum",SIDES));
        view.put("visibleEvidence",Map.of("type","string","minLength",1,"maxLength",350));
        view.put("tailPixelPath",Map.of("type","array","maxItems",32,"items",StyledQualityAgent.object(Map.of(
            "x",Map.of("type","integer","minimum",0,"maximum",31),"y",Map.of("type","integer","minimum",0,"maximum",31)))));
        view.put("tail",Map.of("type","string","enum",SPRITE));view.put("confidence",StyledRecoveryReview.confidence());
        var fields=new LinkedHashMap<String,Object>();
        fields.put("photoEvidence",Map.of("type","string","minLength",1,"maxLength",350));
        fields.put("photoTail",Map.of("type","string","enum",PHOTO));fields.put("photoConfidence",StyledRecoveryReview.confidence());
        fields.put("views",Map.of("type","array","minItems",2,"maxItems",2,"items",StyledQualityAgent.object(view)));
        return StyledQualityAgent.object(fields);
    }
    static JsonNode review(OpenAiResponsesClient client,AiProperties ai,JsonMapper json,byte[] photo,List<byte[]> seeds){
        JsonNode raw;
        try{raw=client.structuredImages(StyledSpriteCodec.qualityRules(json).at("/recovery/tailObservation").asText(),
            "Independently observe the supplied photograph and exact WEST/EAST sprite images. No prior verdict is supplied. "
            +"Describe visible anatomical evidence before choosing each category. Do not judge breed, artistic cuteness, or desired corrections.",images(photo,seeds),schema());
        }catch(AiFailure e){throw new AssetException(502,"QUALITY_"+e.code());}
        var out=grounded(json,raw,seeds);
        out.put("version",VERSION).put("model",ai.model()).put("reviewedAt",Instant.now().toString())
            .put("photoSha256",StyledSpriteCodec.sha(photo)).put("inputSha256",StyledSeedQualityAgent.binding(seeds))
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha());return out;
    }
    static ObjectNode grounded(JsonMapper json,JsonNode raw,List<byte[]> seeds){
        var out=(ObjectNode)assess(json,raw);
        var audit=json.createObjectNode();var failed=new LinkedHashSet<String>();out.path("failedDirections").forEach(d->failed.add(d.asText()));
        for(var view:raw.path("views")){
            String d=view.path("direction").asText();var check=pixelPath(json,seeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(d)),view.path("tailPixelPath"));audit.set(d,check);
            if(view.path("tail").asText().equals("COMPLETE_CONNECTED") && !check.path("completeContour").asBoolean())failed.add(d);
            if(view.path("tail").asText().equals("SHORT_STUB") && out.path("shortTailSupported").asBoolean() && !check.path("visiblePath").asBoolean())failed.add(d);
        }
        out.set("pixelAudit",audit);out.set("failedDirections",json.valueToTree(SIDES.stream().filter(failed::contains).toList()));out.put("passed",failed.isEmpty());
        return out;
    }
    static Map<String,byte[]> images(byte[] photo,List<byte[]> seeds){
        if(seeds.size()!=4)throw StyledRecoveryReview.invalid();
        var images=new LinkedHashMap<String,byte[]>();images.put("Actual photo: tail may be hidden",photo);
        for(String d:SIDES){var board=new BufferedImage(1090,560,BufferedImage.TYPE_INT_RGB);var g=board.createGraphics();
            StyledRecoveryReview.paint(g,1090,560);var source=StyledSpriteCodec.nativeFrame(seeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(d)));
            g.drawImage(source,0,32,512,512,null);g.drawImage(source,560,32,512,512,null);
            g.setColor(new java.awt.Color(115,130,120));for(int n=0;n<=32;n++){g.drawLine(560+n*16,32,560+n*16,544);g.drawLine(560,32+n*16,1072,32+n*16);}
            g.setColor(java.awt.Color.DARK_GRAY);g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,10));
            for(int n=0;n<32;n++){g.drawString(""+n,563+n*16,24);g.drawString(""+n,540,43+n*16);}g.dispose();
            images.put("Exact native32 sprite: "+d,StyledSpriteCodec.png(board));}
        return images;
    }
    static JsonNode pixelPath(JsonMapper json,byte[] image,JsonNode path){
        if(!path.isArray() || path.size()>32)throw StyledRecoveryReview.invalid();
        var im=StyledSpriteCodec.nativeFrame(image);var seen=new HashSet<Integer>();boolean opaque=true,connected=true;
        int px=-1,py=-1,minX=32,minY=32,maxX=-1,maxY=-1;
        for(var point:path){
            for(String f:List.of("x","y"))if(!point.path(f).isIntegralNumber() || point.path(f).asInt()<0 || point.path(f).asInt()>31)throw StyledRecoveryReview.invalid();
            int x=point.path("x").asInt(),y=point.path("y").asInt();
            if(px>=0){
                int distance=Math.max(Math.abs(x-px),Math.abs(y-py));if(distance==0 || distance>3)connected=false;
                // Vision may give nearby waypoints. Inspect every intervening native pixel; never bridge transparent gaps.
                int ax=px,ay=py,dx=Math.abs(x-px),dy=-Math.abs(y-py),sx=px<x?1:-1,sy=py<y?1:-1,error=dx+dy;
                while(ax!=x || ay!=y){int twice=2*error;if(twice>=dy){error+=dy;ax+=sx;}if(twice<=dx){error+=dx;ay+=sy;}
                    if(!seen.add(ay*32+ax))connected=false;opaque&=(im.getRGB(ax,ay)>>>24)!=0;}
            }else{seen.add(y*32+x);opaque&=(im.getRGB(x,y)>>>24)!=0;}
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);px=x;py=y;
        }
        int span=path.isEmpty()?0:Math.max(maxX-minX,maxY-minY);
        return json.valueToTree(Map.of("opaque",opaque,"connected",connected,"points",seen.size(),"span",span,"visiblePath",opaque && connected && !seen.isEmpty(),
            "completeContour",opaque && connected && seen.size()>=4 && span>=3));
    }
    static JsonNode assess(JsonMapper json,JsonNode raw){
        if(!PHOTO.contains(raw.path("photoTail").asText()) || !description(raw.path("photoEvidence"))
            || !raw.path("photoConfidence").isNumber() || !Double.isFinite(raw.path("photoConfidence").asDouble())
            || raw.path("photoConfidence").asDouble()<0 || raw.path("photoConfidence").asDouble()>1
            || !raw.path("views").isArray() || raw.path("views").size()!=2)throw StyledRecoveryReview.invalid();
        boolean shortSupported=raw.path("photoTail").asText().equals("VISIBLE_NATURALLY_SHORT") && raw.path("photoConfidence").asDouble()>=.85;
        var seen=new HashSet<String>();var failed=new ArrayList<String>();
        for(var v:raw.path("views")){
            String d=v.path("direction").asText(),tail=v.path("tail").asText();
            if(!SIDES.contains(d) || !seen.add(d) || !SPRITE.contains(tail) || !description(v.path("visibleEvidence")))throw StyledRecoveryReview.invalid();
            StyledRecoveryReview.checkConfidence(v);
            if(v.path("confidence").asDouble()<.75 || !(tail.equals("COMPLETE_CONNECTED") || (tail.equals("SHORT_STUB") && shortSupported)))failed.add(d);
        }
        return json.valueToTree(Map.of("passed",failed.isEmpty(),"shortTailSupported",shortSupported,
            "failedDirections",SIDES.stream().filter(failed::contains).toList(),"observation",raw));
    }
    static void merge(JsonMapper json,ObjectNode report,JsonNode evidence){
        report.set("generalPropertyReview",report.path("propertyReview").deepCopy());
        report.set("tailEvidence",evidence);
        if(evidence.path("passed").asBoolean())return;
        var failed=new HashSet<String>();evidence.path("failedDirections").forEach(d->failed.add(d.asText()));
        var r=(ObjectNode)report.path("propertyReview");
        for(var view:r.path("views"))if(failed.contains(view.path("direction").asText())){
            var v=(ObjectNode)view;v.put("tailPlausible",false);
            var observation=evidence.at("/observation/views").valueStream().filter(n->n.path("direction").equals(v.path("direction"))).findFirst().orElseThrow(StyledRecoveryReview::invalid);
            double confidence=Math.min(v.path("confidence").asDouble(),observation.path("confidence").asDouble());
            // Invalid/insufficient localization of a claimed complete tail is uncertainty, not permission to redraw arbitrary anatomy.
            if(observation.path("tail").asText().equals("COMPLETE_CONNECTED") && !evidence.path("pixelAudit").path(v.path("direction").asText()).path("completeContour").asBoolean())confidence=Math.min(confidence,.74);
            v.put("confidence",confidence);
            var codes=new LinkedHashSet<String>();v.path("issues").forEach(n->codes.add(n.asText()));codes.add("TAIL_MISSING");v.set("issues",json.valueToTree(codes));
        }
        var issues=new TreeSet<String>();report.path("issues").forEach(n->issues.add(n.asText()));issues.add("SEED_IDENTITY");report.set("issues",json.valueToTree(issues));
        report.put("passed",false).put("identity","FAIL").put("appearance","FAIL");
        String correction=" Show a clearly distinguishable complete connected tail in "+String.join(",",SIDES.stream().filter(failed::contains).toList())+"; a hidden photo does not justify a missing tail or a stump. Keep the same modest inferred tail design and all unaffected anatomy.";
        report.put("repairDescription",report.path("repairDescription").asText()+correction);
        report.put("note","측면 꼬리의 별도 관찰이 통과하지 않아 기본 도트 승인을 보류합니다. 기존 외형 판단과 원본은 보존했습니다.");
    }
    static boolean boundPass(JsonNode report){
        var evidence=report.path("tailEvidence");
        for(String field:List.of("inputSha256","photoSha256","rulesSha256"))if(!report.path(field).isString() || !report.path(field).asText().matches("[a-f0-9]{64}"))return false;
        if(!VERSION.equals(evidence.path("version").asText()) || !evidence.path("passed").isBoolean() || !evidence.path("passed").asBoolean()
            || !evidence.path("failedDirections").isArray() || !evidence.path("failedDirections").isEmpty()
            || !StyledSpriteCodec.qualityRulesSha().equals(evidence.path("rulesSha256").asText()) || evidence.path("model").asText().isBlank()
            || !report.path("inputSha256").equals(evidence.path("inputSha256"))
            || !report.path("photoSha256").equals(evidence.path("photoSha256"))
            || !report.path("rulesSha256").equals(evidence.path("rulesSha256"))
            || !report.path("model").equals(evidence.path("model")))return false;
        try{Instant.parse(evidence.path("reviewedAt").asText());
            for(var v:evidence.at("/observation/views")){
                var audit=evidence.path("pixelAudit").path(v.path("direction").asText());
                if(v.path("tail").asText().equals("COMPLETE_CONNECTED") && !audit.path("completeContour").asBoolean())return false;
                if(v.path("tail").asText().equals("SHORT_STUB") && !audit.path("visiblePath").asBoolean())return false;
            }
            return assess(JsonMapper.builder().build(),evidence.path("observation")).path("passed").asBoolean();}
        catch(RuntimeException e){return false;}
    }
    private static boolean description(JsonNode n){return StyledRecoveryReview.text(n,350) && !n.asText().isBlank();}
}
