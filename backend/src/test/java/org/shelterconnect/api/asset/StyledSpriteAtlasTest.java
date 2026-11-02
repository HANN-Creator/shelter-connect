package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StyledSpriteAtlasTest {
    @ParameterizedTest
    @ValueSource(ints={32,40})
    void packsEverySavedFrameWithoutChangingPixelsAndReusesTheStoredAtlas(int motionSize) throws Exception {
        UUID dog=UUID.randomUUID(), jobId=UUID.randomUUID();
        String prefix=dog+"/"+jobId+"/native-32/";
        var json=JsonMapper.builder().build();
        var seed=json.createObjectNode();var keys=seed.putObject("keys");var hashes=seed.putObject("hashes");
        var original=new HashMap<String,byte[]>();var steps=new ArrayList<StyledAssetStore.Step>();
        int color=1;
        for(String direction:StyledSpriteCodec.DIRECTIONS) {
            String key=prefix+"directions/"+direction+".png";
            byte[] png=solid(32,32,color++);original.put(key,png);
            keys.put(direction,key);hashes.put(direction,hex(png));
        }
        steps.add(new StyledAssetStore.Step("character","BASE",null,"SUCCEEDED",seed,null,0,null));
        for(String action:List.of("IDLE","WALK","SIT"))for(String direction:StyledSpriteCodec.DIRECTIONS) {
            String label=action.toLowerCase(Locale.ROOT)+"-"+direction,key=prefix+"sheets/"+label+".png";
            byte[] png=solid(9*motionSize,motionSize,color++);original.put(key,png);
            var result=json.createObjectNode().put("key",key).put("sha256",hex(png)).put("durationMs",140).put("loop",true);
            steps.add(new StyledAssetStore.Step(label,action,direction,"SUCCEEDED",result,null,0,null));
        }
        var policy=motionSize==40?json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION):null;
        var job=new StyledAssetStore.Job(jobId,dog,"APPROVED",null,StyledSpriteCodec.VERSION,steps,null,
            List.of("BASE","IDLE","WALK","SIT"),null,policy,null);
        assertThat(job.complete()).isTrue();
        var stored=new HashMap<>(original);AssetStorage storage=mock(AssetStorage.class);
        when(storage.asset(anyString())).thenAnswer(call -> {
            byte[] bytes=stored.get(call.getArgument(0));
            if(bytes==null)throw AssetException.unavailable();
            return bytes;
        });
        doAnswer(call -> { stored.put(call.getArgument(0),call.getArgument(1));return null; })
            .when(storage).put(anyString(),any(byte[].class));
        when(storage.sign(anyList())).thenAnswer(call -> {
            Map<String,String> links=new HashMap<>();
            for(String key:((List<String>)call.getArgument(0)))links.put(key,"https://assets.example.invalid/"+key);
            return links;
        });
        var packer=new StyledSpriteAtlas(storage);
        var atlas=packer.ensure(job,motionSize);
        assertThat(packer.ensure(job,motionSize)).isSameAs(atlas);
        assertThat(atlas.width()).isEqualTo(9*motionSize);
        assertThat(atlas.height()).isEqualTo(4*32+12*motionSize);
        verify(storage,times(1)).put(eq(atlas.key()),any(byte[].class));
        var packed=ImageIO.read(new ByteArrayInputStream(stored.get(atlas.key())));
        assertThat(atlas.sha256()).isEqualTo(hex(stored.get(atlas.key())));
        for(String direction:StyledSpriteCodec.DIRECTIONS)
            assertPixels(packed,atlas.y("BASE:"+direction),original.get(keys.path(direction).asText()));
        for(var step:steps.subList(1,steps.size()))
            assertPixels(packed,atlas.y(step.label()),original.get(step.result().path("key").asText()));
        var manifest=new StyledAssetManifest(null,storage,null).manifest(job,false,true);
        assertThat(manifest).containsKey("spriteAtlas");
        var directions=(Map<String,Map<String,Map<String,Object>>>)manifest.get("mapDirections");
        String url=null;
        for(var direction:directions.values())for(var clip:direction.values()) {
            String current=(String)clip.get("spritesheetUrl");
            if(url==null)url=current;
            assertThat(current).isEqualTo(url);
            assertThat(clip.get("sha256")).isEqualTo(atlas.sha256());
            assertThat(clip.get("sourceSha256")).isNotNull();
            var frames=(List<Map<String,Integer>>)clip.get("frames");
            assertThat(frames).hasSize(9);
            assertThat(frames.getFirst().get("y")).isGreaterThanOrEqualTo(128);
        }
        assertThat(url).endsWith(atlas.key());
    }

    private static void assertPixels(BufferedImage packed,int top,byte[] original) throws Exception {
        var strip=ImageIO.read(new ByteArrayInputStream(original));
        for(int y=0;y<strip.getHeight();y++)for(int x=0;x<strip.getWidth();x++)
            assertThat(packed.getRGB(x,top+y)).isEqualTo(strip.getRGB(x,y));
    }
    private static byte[] solid(int width,int height,int color) {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,0xff000000 | color*0x00010101);
        return StyledSpriteCodec.png(image);
    }
    private static String hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
