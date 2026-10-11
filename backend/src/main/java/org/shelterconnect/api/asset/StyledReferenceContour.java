package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Aligned entry-pose comparison; contrast differences locate evidence, never establish a defect. */
final class StyledReferenceContour {
    static final String VERSION="aligned-entry-contour-v1";
    record Evidence(ObjectNode metadata,byte[] board) {}
    private StyledReferenceContour(){}
    static Evidence measure(JsonMapper json,byte[] seed,byte[] entry,JsonNode rules) {
        var source=StyledSpriteCodec.motionFrame(seed);var target=StyledSpriteCodec.motionFrame(entry);
        var out=json.createObjectNode().put("version",VERSION).put("sourceSha256",StyledSpriteCodec.sha(seed))
            .put("entrySha256",StyledSpriteCodec.sha(entry));
        int n=source.getWidth();boolean aligned=target.getWidth()==n;var points=out.putArray("contrastChanges");
        if(aligned)for(int y=0;y<n;y++)for(int x=0;x<n;x++)if((source.getRGB(x,y)>>>24)!=(target.getRGB(x,y)>>>24))aligned=false;
        out.put("sameAlpha",aligned);var policy=rules.at("/recovery/referenceContour");
        int dark=policy.path("darkChannelCeiling").asInt(100),delta=policy.path("minimumChannelDelta").asInt(80);
        int left=n,top=n,right=-1,bottom=-1;
        if(aligned)for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++) {
            boolean interior=true;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if((source.getRGB(x+dx,y+dy)>>>24)==0)interior=false;
            if(!interior)continue;int a=source.getRGB(x,y),b=target.getRGB(x,y);
            int ar=(a>>>16)&255,ag=(a>>>8)&255,ab=a&255,br=(b>>>16)&255,bg=(b>>>8)&255,bb=b&255;
            if((Math.max(ar,Math.max(ag,ab))<dark)==(Math.max(br,Math.max(bg,bb))<dark)
                || Math.max(Math.abs(ar-br),Math.max(Math.abs(ag-bg),Math.abs(ab-bb)))<delta)continue;
            points.addObject().put("x",x).put("y",y);left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        boolean focus=aligned && points.size()>=policy.path("minimumChangedPixels").asInt(4);
        out.put("focusShown",focus);if(!focus)return new Evidence(out,null);
        left=Math.max(0,left-2);top=Math.max(0,top-2);right=Math.min(n,right+3);bottom=Math.min(n,bottom+3);
        out.putArray("boundsXYExclusive").add(left).add(top).add(right).add(bottom);
        int scale=Math.min(24,400/Math.max(right-left,bottom-top));
        var board=new BufferedImage(880,460,BufferedImage.TYPE_INT_RGB);var g=board.createGraphics();StyledRecoveryReview.paint(g,880,460);
        g.drawString("APPROVED REFERENCE: same native region",10,22);g.drawString("ACTUAL FRAME 0: same native region",450,22);
        g.drawImage(source.getSubimage(left,top,right-left,bottom-top),10,40,(right-left)*scale,(bottom-top)*scale,null);
        g.drawImage(target.getSubimage(left,top,right-left,bottom-top),450,40,(right-left)*scale,(bottom-top)*scale,null);g.dispose();
        return new Evidence(out,StyledSpriteCodec.png(board));
    }
}
