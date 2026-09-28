package org.shelterconnect.api.asset;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PhotoUploadImageTest {
    @Test void acceptsExactFiveMiBInputAndRejectsACompressedImageWhoseNormalizedPngExceedsStorageLimit() throws Exception {
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);
        var exact=java.util.Arrays.copyOf(out.toByteArray(),PhotoUploadImage.MAX_BYTES);
        assertThat(PhotoUploadImage.normalize(exact)).isNotEmpty();
        var noise=new BufferedImage(2048,2048,BufferedImage.TYPE_INT_RGB);var random=new java.util.Random(72);
        for(int y=0;y<2048;y++) for(int x=0;x<2048;x++) noise.setRGB(x,y,random.nextInt(1<<24));
        out.reset();ImageIO.write(noise,"jpeg",out);
        assertThat(out.size()).isLessThan(PhotoUploadImage.MAX_BYTES);
        assertThatThrownBy(()->PhotoUploadImage.normalize(out.toByteArray())).isInstanceOfSatisfying(AssetException.class,ex->assertThat(ex.code).isEqualTo("PHOTO_TOO_LARGE"));
    }
    @Test void validatesDecodedPixelsAndStripsTheOriginalContainer() throws Exception {
        var image=new BufferedImage(3000,1000,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,"jpg",out);
        var png=PhotoUploadImage.normalize(out.toByteArray());var normalized=ImageIO.read(new ByteArrayInputStream(png));
        assertThat(normalized.getWidth()).isEqualTo(2048);assertThat(normalized.getHeight()).isEqualTo(682);
        assertThatThrownBy(()->PhotoUploadImage.normalize("not an image".getBytes())).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->PhotoUploadImage.normalize(new byte[PhotoUploadImage.MAX_BYTES+1])).isInstanceOf(AssetException.class);
        out.reset();ImageIO.write(new BufferedImage(5000,4000,BufferedImage.TYPE_BYTE_GRAY),"png",out);
        assertThatThrownBy(()->PhotoUploadImage.normalize(out.toByteArray())).isInstanceOf(AssetException.class);
    }
}
