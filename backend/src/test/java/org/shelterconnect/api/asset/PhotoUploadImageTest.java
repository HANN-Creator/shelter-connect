package org.shelterconnect.api.asset;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PhotoUploadImageTest {
    @Test void validatesDecodedPixelsAndStripsTheOriginalContainer() throws Exception {
        var image=new BufferedImage(3000,1000,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,"jpg",out);
        var png=PhotoUploadImage.normalize(out.toByteArray());var normalized=ImageIO.read(new ByteArrayInputStream(png));
        assertThat(normalized.getWidth()).isEqualTo(2048);assertThat(normalized.getHeight()).isEqualTo(682);
        assertThatThrownBy(()->PhotoUploadImage.normalize("not an image".getBytes())).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->PhotoUploadImage.normalize(new byte[8*1024*1024+1])).isInstanceOf(AssetException.class);
        out.reset();ImageIO.write(new BufferedImage(5000,4000,BufferedImage.TYPE_BYTE_GRAY),"png",out);
        assertThatThrownBy(()->PhotoUploadImage.normalize(out.toByteArray())).isInstanceOf(AssetException.class);
    }
}
