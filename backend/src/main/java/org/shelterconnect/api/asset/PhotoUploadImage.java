package org.shelterconnect.api.asset;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;
import javax.imageio.ImageIO;

final class PhotoUploadImage {
    static byte[] normalize(byte[] bytes) {
        if(bytes.length==0 || bytes.length>8*1024*1024) throw new AssetException(413,"PHOTO_TOO_LARGE");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw AssetException.invalid();
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if(!java.util.Set.of("png","jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw AssetException.invalid();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<16 || height<16 || width>8192 || height>8192 || (long)width*height>16000000) throw new AssetException(413,"PHOTO_TOO_LARGE");
                var image=reader.read(0);double scale=Math.min(1,2048.0/Math.max(width,height));
                var clean=new BufferedImage(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),BufferedImage.TYPE_INT_ARGB);
                var g=clean.createGraphics();try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,clean.getWidth(),clean.getHeight(),null); } finally { g.dispose(); }
                var output=new ByteArrayOutputStream();ImageIO.write(clean,"png",output);
                if(output.size()>8*1024*1024) throw new AssetException(413,"PHOTO_TOO_LARGE");
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch(AssetException ex) { throw ex; }
        catch(Exception ex) { throw AssetException.invalid(); }
    }
}
