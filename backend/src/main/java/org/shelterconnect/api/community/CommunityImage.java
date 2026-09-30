package org.shelterconnect.api.community;

import org.shelterconnect.api.web.FeatureException;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;
import javax.imageio.ImageIO;

final class CommunityImage {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static byte[] normalize(byte[] bytes) {
        if(bytes.length==0 || bytes.length>CommunityImage.MAX_BYTES) throw new FeatureException(413,"PHOTO_TOO_LARGE","사진은 5MiB 이하, 최대 1600만 화소여야 해요.");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw FeatureException.invalid();
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if(!java.util.Set.of("png","jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw FeatureException.invalid();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<16 || height<16 || width>8192 || height>8192 || (long)width*height>16000000) throw new FeatureException(413,"PHOTO_TOO_LARGE","사진은 5MiB 이하, 최대 1600만 화소여야 해요.");
                var image=reader.read(0);double scale=Math.min(1,2048.0/Math.max(width,height));
                var clean=new BufferedImage(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),BufferedImage.TYPE_INT_ARGB);
                var g=clean.createGraphics();try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,clean.getWidth(),clean.getHeight(),null); } finally { g.dispose(); }
                var output=new ByteArrayOutputStream();ImageIO.write(clean,"png",output);
                if(output.size()>CommunityImage.MAX_BYTES) throw new FeatureException(413,"PHOTO_TOO_LARGE","사진은 5MiB 이하, 최대 1600만 화소여야 해요.");
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch(FeatureException ex) { throw ex; }
        catch(Exception ex) { throw FeatureException.invalid(); }
    }
}
