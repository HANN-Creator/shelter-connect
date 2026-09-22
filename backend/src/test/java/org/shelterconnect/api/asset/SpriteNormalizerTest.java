package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SpriteNormalizerTest {
    static byte[] frame(int dx,int dy) {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=15+dy;y<60+dy;y++) for(int x=15+dx;x<48+dx;x++) image.setRGB(x,y,0xff996622);
        return SpriteNormalizer.png(image);
    }
    @Test void clipsKeepActualFrameCountAndSharedScale() {
        var base=SpriteNormalizer.base(List.of(frame(0,0)));
        var frames=new ArrayList<byte[]>();for(int i=0;i<16;i++)frames.add(frame(0,i==3?-2:0));
        var clip=SpriteNormalizer.clip(frames,base,AssetAction.WALK);
        assertThat(clip.frameCount()).isEqualTo(16);
        assertThat(clip.offsets().get(3)).containsEntry("y",2);
        assertThat(SpriteNormalizer.dimensions(clip.sheet(),1024)).containsExactly(1024,64);
        assertThat(SpriteNormalizer.clip(frames,base,AssetAction.RUN).offsets().get(3)).containsEntry("y",0);
    }
    @Test void truncatedOpaqueOversizedAndWrongCountAreRejectedBeforePublication() {
        assertThatThrownBy(()->SpriteNormalizer.base(List.of(new byte[]{1,2,3}))).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->SpriteNormalizer.clip(Collections.nCopies(7,frame(0,0)),frame(0,0),AssetAction.IDLE)).isInstanceOf(AssetException.class);
        var opaque=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(()->SpriteNormalizer.base(List.of(SpriteNormalizer.png(opaque)))).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->SpriteNormalizer.base(List.of(frame(-15,0)))).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->SpriteNormalizer.reference(SpriteNormalizer.png(new BufferedImage(4097,1,BufferedImage.TYPE_INT_RGB)))).isInstanceOf(AssetException.class);
    }
    @Test void inputIsReencodedAtBoundedResolution() {
        var image=new BufferedImage(2048,1024,BufferedImage.TYPE_INT_RGB);
        assertThat(SpriteNormalizer.dimensions(SpriteNormalizer.reference(SpriteNormalizer.png(image)),1024)).containsExactly(1024,512);
    }
}
