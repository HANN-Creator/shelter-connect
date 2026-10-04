package org.shelterconnect.api.asset;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class StyledSpriteCodecTest {
    final JsonMapper json=JsonMapper.builder().build();
    byte[] image(int size) throws Exception {
        var im=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int y=5;y<size-2;y++)for(int x=8;x<size-5;x++)im.setRGB(x,y,0xff123456);
        var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();
    }
    @Test void packagedBuilderUsesExactPhotoAndStyleSeparationAndReviewedCrop() throws Exception {
        byte[] photo=image(64);var traits=json.valueToTree(Map.of("sourcePhotoSha256",StyledSpriteCodec.sha(photo),"faceBox",List.of(.1,.1,.7,.7),"identityDescription","short brown fur and upright ears","motionDescription","compact brown puppy","rearDescription","tail unseen in photo","seed",4242,"reviewNote","photo and face crop visually reviewed"));
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var request=codec.character(UUID.randomUUID(),traits,photo);
        assertThat(request.path("method").asText()).isEqualTo("create_from_concept");
        assertThat(request.at("/image_size/width").asInt()).isEqualTo(32);
        assertThat(request.path("description").asText()).contains("short brown fur and upright ears","gently closed mouth");
        assertThat(request.path("concept_image")).isNotEqualTo(request.path("reference_image"));
        assertThatThrownBy(()->codec.character(UUID.randomUUID(),traits,new byte[]{1})).isInstanceOf(AssetException.class).hasMessage("SOURCE_PHOTO_CHANGED");
        var motion=codec.motion(traits,"WALK","north",image(32));
        assertThat(motion.path("first_frame")).isEqualTo(motion.path("last_frame"));
        assertThat(motion.path("description").asText()).contains("No visible eyes, nose, mouth");
        assertThat(motion.path("subject_description").asText()).isEqualTo("tail unseen in photo");
        assertThat(codec.motion(traits,"SIT","south",image(32)).has("last_frame")).isFalse();
    }
    @Test void nativeSheetPreservesEveryPixelAndRejectsWrongSizeCountOrSeed() throws Exception {
        byte[] seed=image(32);var frames=new ArrayList<>(Collections.nCopies(9,seed));byte[] sheet=StyledSpriteCodec.sheet(frames,seed);
        var im=ImageIO.read(new ByteArrayInputStream(sheet));assertThat(im.getWidth()).isEqualTo(288);
        for(int i=0;i<9;i++)assertThat(im.getRGB(8+32*i,5)).isEqualTo(0xff123456);
        assertThatThrownBy(()->StyledSpriteCodec.sheet(frames.subList(0,8),seed)).isInstanceOf(AssetException.class);
        assertThatThrownBy(()->StyledSpriteCodec.nativeFrame(image(64))).isInstanceOf(AssetException.class);
        var changed=ImageIO.read(new ByteArrayInputStream(seed));changed.setRGB(8,5,0xff998877);var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);
        frames.set(0,out.toByteArray());assertThatThrownBy(()->StyledSpriteCodec.sheet(frames,seed)).hasMessage("STYLED_SOURCE_FRAME_CHANGED");
    }
}
