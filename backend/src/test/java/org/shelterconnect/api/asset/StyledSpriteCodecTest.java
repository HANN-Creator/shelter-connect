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
        assertThat(request.path("description").asText()).contains("short brown fur and upright ears","Closed neutral mouth");
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
    @Test void idleEditKeepsNineDefectiveFramesAndOwnDirectionForProviderRepair() throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        byte[] seed=image(32);var original=StyledSpriteCodec.rawSheet(Collections.nCopies(9,seed));
        var payload=codec.idleEdit("north",original,147);
        assertThat(payload.path("frames").size()).isEqualTo(9);
        assertThat(payload.path("seed").asInt()).isEqualTo(147);
        assertThat(payload.path("description").asText()).contains("Frame zero is the approved", "standing height", "No visible eyes", "north facing");
        for(var frame:payload.path("frames")) {
            var decoded=StyledSpriteCodec.nativeFrame(StyledPixelLabClient.decode(frame.at("/image/base64").asText()));
            assertThat(decoded.getRGB(8,5)).isEqualTo(0xff123456);
        }
        assertThatThrownBy(()->codec.idleEdit("diagonal",original,147)).isInstanceOf(AssetException.class);
    }
    @Test void tailEditUsesAllNineFramesAndPaletteLockPreservesRawEvidence() throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        byte[] seed=image(32);var changed=ImageIO.read(new ByteArrayInputStream(seed));
        changed.setRGB(31,18,0xffdca122);changed.setRGB(10,10,0xff884422);
        var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);
        var raw=new ArrayList<>(Collections.nCopies(9,out.toByteArray()));byte[] before=raw.getFirst().clone();
        var payload=codec.tailEdit("east",StyledSpriteCodec.rawSheet(raw),123);
        assertThat(payload.path("frames").size()).isEqualTo(9);assertThat(payload.path("seed").asInt()).isEqualTo(123);
        assertThat(payload.path("description").asText()).contains("COMPLETE","ONE transparent pixel");
        assertThat(StyledSpriteCodec.nativeFrame(StyledPixelLabClient.decode(payload.at("/frames/0/image/base64").asText())).getRGB(31,18)).isEqualTo(0xffdca122);
        var restored=StyledSpriteCodec.restoreEditPalette(raw,seed);
        assertThat(restored.getFirst()).isEqualTo(seed);assertThat(raw.getFirst()).isEqualTo(before);
        assertThat(StyledSpriteCodec.nativeFrame(raw.getFirst()).getRGB(31,18)>>>24).isEqualTo(255);
        // Geometry is not erased to force a pass: the bad edge survives frames 1–8.
        assertThat(StyledSpriteCodec.nativeFrame(restored.get(8)).getRGB(31,18)).isEqualTo(0xff123456);
        assertThat(restored).hasSize(9);
    }
    @Test void marginEditKeepsRequestedWalkOrSitAndNeverCropsInputEdges() throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        var image=ImageIO.read(new ByteArrayInputStream(image(32)));image.setRGB(31,18,0xff123456);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        byte[] sheet=StyledSpriteCodec.rawSheet(Collections.nCopies(9,out.toByteArray()));
        for(String action:List.of("WALK","SIT")) {
            var payload=codec.marginEdit(action,"north",sheet,147);
            assertThat(payload.path("description").asText()).contains(action,"ONE transparent pixel","Do not amputate","north facing","No visible eyes");
            assertThat(payload.path("description").asText().length()).isLessThanOrEqualTo(2000);
            assertThat(payload.path("frames").size()).isEqualTo(9);
            for(var frame:payload.path("frames"))assertThat(StyledSpriteCodec.nativeFrame(StyledPixelLabClient.decode(frame.at("/image/base64").asText())).getRGB(31,18)).isEqualTo(0xff123456);
        }
        assertThatThrownBy(()->codec.marginEdit("BASE","north",sheet,147)).isInstanceOf(AssetException.class);
    }
    @Test void seedIdleStartsFromNineApprovedPosesNotTheDefectiveAnimation() throws Exception {
        var codec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        byte[] seed=image(32);var payload=codec.seedIdle("north",seed,147);
        assertThat(payload.path("description").asText()).contains("identical approved", "silhouette pixel", "north facing", "No visible eyes");
        assertThat(payload.path("frames").size()).isEqualTo(9);
        for(var frame:payload.path("frames"))assertThat(StyledSpriteCodec.nativeFrame(StyledPixelLabClient.decode(frame.at("/image/base64").asText())).getRGB(8,5)).isEqualTo(0xff123456);
    }
    @Test void mirroredMotionPreservesAllPixelsAndRefusesAsymmetricCoatsOrShapes() throws Exception {
        var root=java.nio.file.Path.of("scripts/fixtures/motion-continuation");
        byte[] east=java.nio.file.Files.readAllBytes(root.resolve("east.png")),west=java.nio.file.Files.readAllBytes(root.resolve("west.png"));
        for(String action:List.of("idle","walk","sit")) {
            byte[] sheet=java.nio.file.Files.readAllBytes(root.resolve(action+"-east.png"));
            var source=StyledSpriteCodec.frames(sheet);var mirrored=StyledSpriteCodec.mirroredMotion(sheet,east,west);
            assertThat(mirrored).hasSize(9);
            for(int i=0;i<9;i++) {
                var a=StyledSpriteCodec.nativeFrame(mirrored.get(i));var b=StyledSpriteCodec.nativeFrame(source.get(i));
                for(int y=0;y<32;y++)for(int x=0;x<32;x++)assertThat(a.getRGB(x,y)).isEqualTo(b.getRGB(31-x,y));
            }
            var changed=StyledSpriteCodec.nativeFrame(west);changed.setRGB(12,10,0xffffffff);
            var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);
            assertThatThrownBy(()->StyledSpriteCodec.mirroredMotion(sheet,east,out.toByteArray())).hasMessage("MOTION_SEEDS_NOT_SYMMETRIC");
            changed.setRGB(0,0,0xff123456);out.reset();ImageIO.write(changed,"png",out);
            assertThatThrownBy(()->StyledSpriteCodec.mirroredMotion(sheet,east,out.toByteArray())).hasMessage("MOTION_SEEDS_NOT_SYMMETRIC");
        }
    }
}
