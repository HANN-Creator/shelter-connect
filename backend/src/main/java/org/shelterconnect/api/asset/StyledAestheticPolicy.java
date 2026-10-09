package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** User-selected cosmetic warnings. Never waives missing anatomy, bad direction or unreadable eyes. */
final class StyledAestheticPolicy {
    static final String VERSION="aesthetic-warnings-v1";
    private StyledAestheticPolicy() {}
    static boolean enabled(JsonNode report){return report!=null && VERSION.equals(report.path("aestheticPolicy").asText());}
    static boolean hasWarnings(JsonNode report){return report!=null && (hasNestedWarnings(report)
        || hasNestedWarnings(report.path("rawEditReview")) || hasNestedWarnings(report.path("restoredReview")));}
    private static boolean hasNestedWarnings(JsonNode r){return r.isObject() && r.path("qualityWarnings").isArray() && !r.path("qualityWarnings").isEmpty();}
    static ObjectNode base(JsonMapper json,JsonNode original) {
        var result=(ObjectNode)original.deepCopy();result.put("aestheticPolicy",VERSION);var warnings=result.putArray("qualityWarnings");
        for(var node:result.path("views")) {
            if(!node.isObject())throw StyledRecoveryReview.invalid();
            var v=(ObjectNode)node;
            for(String field:StyledRecoveryReview.BASE.keySet())if(!v.path(field).isBoolean())throw StyledRecoveryReview.invalid();
            if(!v.path("issues").isArray())throw StyledRecoveryReview.invalid();
            for(var code:v.path("issues"))if(!code.isString() || !StyledRecoveryReview.BASE_CODES.contains(code.asText()))throw StyledRecoveryReview.invalid();
            var codes=new TreeSet<String>();v.path("issues").forEach(n->codes.add(n.asText()));
            if(!v.path("styleMatches").asBoolean() || codes.remove("STYLE_DRIFT")) {
                codes.remove("STYLE_DRIFT");v.put("styleMatches",true);
                warning(json,warnings,v.path("direction").asText(),"STYLE_VARIATION",v.path("note").asText());
            }
            if(v.path("coatObservationUncertain").asBoolean())
                warning(json,warnings,v.path("direction").asText(),"COAT_APPEARANCE_UNCERTAIN",v.path("coatObservation").path("evidence").asText());
            v.set("issues",json.valueToTree(codes));
            // A low overall confidence with every concrete property passing is not a new image defect.
            boolean advisory=v.path("confidence").asDouble()<.75 && codes.isEmpty()
                && StyledRecoveryReview.BASE.keySet().stream().allMatch(f->v.path(f).isBoolean() && v.path(f).asBoolean());
            v.put("confidenceAdvisory",advisory);
            if(advisory)warning(json,warnings,v.path("direction").asText(),"COSMETIC_CONFIDENCE",v.path("note").asText());
        }
        return result;
    }
    static void warning(JsonMapper json,tools.jackson.databind.node.ArrayNode out,String direction,String code,String evidence) {
        out.addObject().put("direction",direction).put("code",code).put("severity","WARNING").put("blocking",false).put("evidence",evidence);
    }
    static boolean lowConfidenceBlocks(JsonNode report,JsonNode view){return view.path("confidence").asDouble()<.75
        && !(enabled(report) && view.path("confidenceAdvisory").asBoolean());}
    static String repairDescription(JsonNode report,List<String> directions) {
        var parts=new ArrayList<String>();
        for(var v:report.at("/propertyReview/views"))if(directions.contains(v.path("direction").asText())) {
            var codes=new TreeSet<String>();v.path("issues").forEach(n->codes.add(n.asText()));
            StyledRecoveryReview.BASE.forEach((field,code)->{if(!v.path(field).asBoolean())codes.add(code);});
            for(String field:List.of("edgeDirections","marginDirections"))if(report.path(field).valueStream().anyMatch(d->d.asText().equals(v.path("direction").asText())))codes.add("CANVAS_CLIPPING");
            String detail=codes.contains("COAT_MISMATCH")?" Missing "+v.path("coatRepairScope").asText()+" fur patch. Photo pattern: "+v.at("/coatObservation/photoPattern").asText():
                codes.contains("SEED_IDENTITY")?" Photo features: "+v.path("observedPhotoMarkings").asText():"";
            String findings=v.path("direction").asText()+": "+String.join(",",codes)+".";
            // Keep every typed defect; budget only the descriptive quotation, whose full source stays in history.
            int remaining=Math.max(0,850/Math.max(1,directions.size())-findings.length());
            if(detail.length()>remaining)detail=detail.substring(0,remaining);
            parts.add(findings+detail);
        }
        return "Correct ONLY these confirmed findings: "+String.join("; ",parts)+". Cosmetic warnings are not edit targets. Preserve other views and all existing anatomy; do not reinterpret photo-hidden tail shape or height.";
    }
    static boolean paletteWarning(JsonNode first,JsonNode second) {
        String a=StyledMotionReview.property(first,"palette").path("state").asText();
        String b=second==null?a:StyledMotionReview.property(second,"palette").path("state").asText();
        return (!a.equals("PASS") || !b.equals("PASS")) && !(a.equals("FAIL") && b.equals("FAIL"));
    }
    static boolean permitsPaletteWarning(JsonNode report) {
        try {return enabled(report) && report.path("qualityWarnings").valueStream().anyMatch(w->w.path("code").asText().equals("PALETTE_UNCERTAIN") && !w.path("blocking").asBoolean(true))
            && paletteWarning(report.path("initialVision"),report.has("consistencyReview")?report.path("consistencyReview"):null);
        }catch(AssetException invalid){return false;}
    }
}
