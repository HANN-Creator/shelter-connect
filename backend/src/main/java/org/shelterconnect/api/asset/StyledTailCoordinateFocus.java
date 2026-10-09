package org.shelterconnect.api.asset;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Lossless visual aids for a fallible coordinate observation; never edits or snaps native pixels. */
final class StyledTailCoordinateFocus {
    static final String VERSION="tail-coordinate-focus-v1";
    static final int CELL=36,PAD=20,TOP=30;
    record Input(Map<String,byte[]> images,ObjectNode metadata,String task){}
    private StyledTailCoordinateFocus(){}

    static Input prepare(JsonMapper json,byte[] photo,List<byte[]> seeds,String direction,JsonNode evidence){
        if(!StyledSeedTailEvidence.SIDES.contains(direction) || seeds.size()!=4)throw StyledRecoveryReview.invalid();
        var previous=evidence.at("/observation/views").valueStream().filter(v->direction.equals(v.path("direction").asText())).findFirst().orElseThrow(StyledRecoveryReview::invalid);
        byte[] bytes=seeds.get(StyledSpriteCodec.DIRECTIONS.indexOf(direction));var source=StyledSpriteCodec.nativeFrame(bytes);
        // Validated native coordinates are only a region hint; always also supply the complete image.
        StyledSeedTailEvidence.pixelPath(json,bytes,previous.path("tailPixelPath"));
        int left=32,top=32,right=-1,bottom=-1;
        var invalid=json.createArrayNode();
        for(var p:previous.path("tailPixelPath")){
            int x=p.path("x").asInt(),y=p.path("y").asInt();
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
            if((source.getRGB(x,y)>>>24)==0)invalid.add(p.deepCopy());
        }
        if(right<0){left=top=0;right=bottom=32;}
        else{left=Math.max(0,left-3);top=Math.max(0,top-3);right=Math.min(32,right+4);bottom=Math.min(32,bottom+4);}
        var full=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var g=full.createGraphics();
        g.setColor(new Color(237,240,234));g.fillRect(0,0,512,512);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source,0,0,512,512,null);g.dispose();
        var crop=new BufferedImage((right-left)*CELL+PAD*2,(bottom-top)*CELL+TOP+PAD,BufferedImage.TYPE_INT_RGB);
        g=crop.createGraphics();g.setColor(new Color(237,240,234));g.fillRect(0,0,crop.getWidth(),crop.getHeight());
        g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,10));g.setColor(Color.DARK_GRAY);g.drawString(direction.toUpperCase(Locale.ROOT)+" exact native x,y cells",PAD,18);
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int sx=PAD+(x-left)*CELL,sy=TOP+(y-top)*CELL,argb=source.getRGB(x,y);
            g.setColor((argb>>>24)==0?new Color(229,235,229):new Color(argb,true));g.fillRect(sx,sy,CELL,CELL);
            g.setColor(new Color(125,137,130));g.drawRect(sx,sy,CELL,CELL);
            String label=x+","+y;g.setColor(Color.WHITE);g.fillRect(sx+1,sy+1,g.getFontMetrics().stringWidth(label)+1,10);
            g.setColor(Color.BLACK);g.drawString(label,sx+1,sy+9);
        }g.dispose();
        var images=new LinkedHashMap<String,byte[]>();images.put("Actual photo; tail may be hidden",photo);
        images.put("Complete exact "+direction+" sprite, integer enlargement",StyledSpriteCodec.png(full));
        images.put("Native coordinate cells; "+direction+" crop x["+left+","+right+"), y["+top+","+bottom+")",StyledSpriteCodec.png(crop));
        var metadata=json.createObjectNode().put("version",VERSION).put("direction",direction).put("sourceSha256",StyledSpriteCodec.sha(bytes));
        metadata.set("cropBoundsOriginalXYExclusive",json.valueToTree(List.of(left,top,right,bottom)));
        metadata.set("transparentCoordinates",invalid);
        var hashes=json.createObjectNode();images.forEach((label,b)->hashes.put(label,StyledSpriteCodec.sha(b)));metadata.set("imageHashes",hashes);
        var task=metadata.deepCopy();task.set("previousObservation",previous.deepCopy());task.set("pixelAudit",evidence.path("pixelAudit").path(direction));
        var assessment=task.putObject("photoAssessment");for(String key:List.of("photoTail","photoConfidence","photoEvidence"))assessment.set(key,evidence.path("observation").path(key));
        task.set("nativeGeometry",StyledSeedTailEvidence.geometry(json,seeds,true).path(direction));
        String text="Refine ONLY "+direction+" on unchanged pixels. Use the complete unlabelled image to identify attachment, contour and actual tip; "
            +"then read absolute native x,y from cell labels. Labels are an overlay, not anatomy. The crop is only a fallible region hint. "
            +"Do not confuse a crop edge with a tail tip, truncate a path just before an invalid point, or trace body pixels to obtain a passing result. "
            +"Keep uncertainty if the actual anatomy cannot be localized. Other directions and photo assessment will be preserved. Data: "+json.writeValueAsString(task);
        metadata.put("taskSha256",StyledSpriteCodec.sha(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new Input(images,metadata,text);
    }

    static JsonNode replace(JsonMapper json,JsonNode original,String direction,JsonNode replacement){
        if(!replacement.isObject() || replacement.size()!=5 || !replacement.path("direction").asText().equals(direction))throw StyledRecoveryReview.invalid();
        var merged=(ObjectNode)original.deepCopy();var views=json.createArrayNode();
        for(var v:original.path("views"))views.add(v.path("direction").asText().equals(direction)?replacement.deepCopy():v.deepCopy());
        merged.set("views",views);return merged;
    }
}
