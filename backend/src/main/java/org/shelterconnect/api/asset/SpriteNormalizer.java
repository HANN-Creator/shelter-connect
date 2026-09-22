package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

final class SpriteNormalizer {
    record Clip(byte[] sheet, int frameCount, List<Map<String,Integer>> offsets) {}
    private SpriteNormalizer() {}
    static int[] dimensions(byte[] data,int maximum) { var im=decode(data,maximum); return new int[]{im.getWidth(),im.getHeight()}; }
    static byte[] reference(byte[] data) {
        var input=decode(data,4096);
        double scale=Math.min(1,1024.0/Math.max(input.getWidth(),input.getHeight()));
        var output=new BufferedImage(Math.max(1,(int)(input.getWidth()*scale)),Math.max(1,(int)(input.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
        var g=output.createGraphics(); g.drawImage(input,0,0,output.getWidth(),output.getHeight(),null); g.dispose();
        return png(output); // Re-encoding drops EXIF and prevents forwarding non-image payloads.
    }
    static byte[] base(List<byte[]> candidates) {
        for (byte[] candidate:candidates) {
            try {
                var im=sprite(candidate); var bounds=bounds(im);
                int dx=32-(bounds[0]+bounds[2])/2, dy=60-bounds[3];
                return png(translate(im,dx,dy));
            } catch (AssetException ignored) { /* Try the next candidate, never generate another paid request. */ }
        }
        throw new AssetException(422,"SPRITE_REQUIRES_REVIEW");
    }
    static Clip clip(List<byte[]> frames,byte[] base,AssetAction action) {
        // Pro produces sixteen native 64px frames. A short/partial clip cannot be published.
        if (frames.size()!=16) throw new AssetException(422,"INVALID_FRAME_COUNT");
        var sheet=new BufferedImage(64*frames.size(),64,BufferedImage.TYPE_INT_ARGB);
        var offsets=new ArrayList<Map<String,Integer>>();
        for (int i=0;i<frames.size();i++) {
            var im=sprite(frames.get(i)); int dy=60-bounds(im)[3];
            // A running dog's airborne frames retain their vertical motion; no per-frame resizing.
            if (action==AssetAction.RUN) dy=0;
            im=translate(im,0,dy);
            boolean lockFirst=i==0 && (action==AssetAction.SIT || action==AssetAction.LIE_DOWN);
            if (lockFirst) im=sprite(base);
            var g=sheet.createGraphics(); g.drawImage(im,i*64,0,null); g.dispose();
            offsets.add(Map.of("x",0,"y",lockFirst?0:dy));
        }
        return new Clip(png(sheet),frames.size(),List.copyOf(offsets));
    }
    private static BufferedImage sprite(byte[] data) {
        var im=decode(data,64);
        if (im.getWidth()!=64 || im.getHeight()!=64 || !im.getColorModel().hasAlpha()) throw bad();
        int opaque=0;
        for(int y=0;y<64;y++) for(int x=0;x<64;x++) if((im.getRGB(x,y)>>>24)>0) opaque++;
        if (opaque<32 || opaque>3600) throw bad();
        int[] b=bounds(im);
        if(b[0]==0 || b[1]==0 || b[2]==64 || b[3]==64) throw bad();
        return im;
    }
    private static int[] bounds(BufferedImage im) {
        int l=64,t=64,r=0,b=0;
        for(int y=0;y<64;y++) for(int x=0;x<64;x++) if((im.getRGB(x,y)>>>24)>0) { l=Math.min(l,x);t=Math.min(t,y);r=Math.max(r,x+1);b=Math.max(b,y+1); }
        return new int[]{l,t,r,b};
    }
    private static BufferedImage translate(BufferedImage im,int dx,int dy) {
        var b=bounds(im);
        if(b[0]+dx<1 || b[1]+dy<1 || b[2]+dx>63 || b[3]+dy>63) throw bad();
        var out=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        var g=out.createGraphics();g.drawImage(im,dx,dy,null);g.dispose();return out;
    }
    private static BufferedImage decode(byte[] data,int maximum) {
        if(data.length==0 || data.length>8*1024*1024) throw bad();
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw bad();
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if(!Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw bad();
                if(reader.getWidth(0)<1 || reader.getHeight(0)<1 || reader.getWidth(0)>maximum || reader.getHeight(0)>maximum) throw bad();
                return reader.read(0);
            } finally { reader.dispose(); }
        } catch (Exception e) { throw bad(); }
    }
    static byte[] png(BufferedImage image) {
        try(var out=new ByteArrayOutputStream()) { ImageIO.write(image,"png",out);return out.toByteArray(); }
        catch(IOException e) { throw bad(); }
    }
    private static AssetException bad() { return new AssetException(422,"INVALID_SPRITE_IMAGE"); }
}
