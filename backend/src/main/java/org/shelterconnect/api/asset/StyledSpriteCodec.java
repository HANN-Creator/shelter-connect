package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The approved CLI builds payloads; Java stores native pixels without resampling. */
@Component
public class StyledSpriteCodec {
    public static final String VERSION="cozy32-photo-style-v1";
    public static final String IDLE_EDIT_VERSION="calm-idle-edit-v1";
    public static final String MARGIN_EDIT_VERSION="frame-margin-edit-v1";
    public static final String SEED_IDLE_VERSION="approved-seed-idle-v1";
    public static final String MIRROR_VERSION="symmetric-approved-motion-v1";
    static final List<String> DIRECTIONS=List.of("south","north","west","east");
    static final List<String> ACTIONS=List.of("IDLE","WALK","RUN","SNIFF","TAIL_WAG","BACK_OFF","SIT","LIE_DOWN");
    private final JsonMapper json;
    private final String python;
    public StyledSpriteCodec(JsonMapper json,@Value("${app.assets.harness-python:python3}") String python) { this.json=json;this.python=python; }
    static JsonNode validateTraits(JsonNode t) {
        AssetInput.fields(t,"sourcePhotoSha256","faceBox","identityDescription","motionDescription","rearDescription","seed","reviewNote");
        if(!AssetInput.text(t,"sourcePhotoSha256",64).matches("[a-f0-9]{64}")) throw AssetException.invalid();
        AssetInput.text(t,"identityDescription",850); AssetInput.text(t,"motionDescription",300); AssetInput.text(t,"rearDescription",300);
        if(AssetInput.text(t,"reviewNote",2000).length()<20) throw AssetException.invalid();
        if(!t.path("seed").isIntegralNumber() || !t.path("seed").canConvertToInt() || t.path("seed").asInt()<0) throw AssetException.invalid();
        var b=t.path("faceBox");
        if(!b.isArray() || b.size()!=4 || b.valueStream().anyMatch(v->!v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble()<0 || v.asDouble()>1)
            || b.get(0).asDouble()>=b.get(2).asDouble() || b.get(1).asDouble()>=b.get(3).asDouble()) throw AssetException.invalid();
        return t.deepCopy();
    }
    public JsonNode character(UUID dog,JsonNode traits,byte[] photo) {
        return character(dog,traits,photo,json.createObjectNode());
    }
    public JsonNode character(UUID dog,JsonNode traits,byte[] photo,JsonNode quality) {
        if(!sha(photo).equals(traits.path("sourcePhotoSha256").asText())) throw new AssetException(409,"SOURCE_PHOTO_CHANGED");
        return payload(Map.of("mode","character","dogId",dog,"traits",traits,"quality",quality),"photo-1.png",photo);
    }
    public JsonNode motion(JsonNode traits,String action,String direction,byte[] seed) {
        return motion(traits,action,direction,seed,json.createObjectNode());
    }
    public JsonNode motion(JsonNode traits,String action,String direction,byte[] seed,JsonNode quality) {
        nativeFrame(seed);
        if(!ACTIONS.contains(action) || !DIRECTIONS.contains(direction)) throw AssetException.invalid();
        return payload(Map.of("mode","motion","traits",traits,"action",action,"direction",direction,"quality",quality),"seed.png",seed);
    }
    public JsonNode tailEdit(String direction,byte[] sheet,int seed) {
        if(!DIRECTIONS.contains(direction) || seed<0)throw AssetException.invalid();
        frames(sheet); // Bounded nine native frames; defective geometry is the edit input.
        return payload(Map.of("mode","tail-edit","direction",direction,"seed",seed),"sheet.png",sheet);
    }
    public JsonNode idleEdit(String direction,byte[] sheet,int seed) {
        if(!DIRECTIONS.contains(direction) || seed<0)throw AssetException.invalid();
        frames(sheet);
        return payload(Map.of("mode","idle-edit","direction",direction,"seed",seed),"sheet.png",sheet);
    }
    public JsonNode marginEdit(String action,String direction,byte[] sheet,int seed) {
        if(!ACTIONS.contains(action) || !DIRECTIONS.contains(direction) || seed<0)throw AssetException.invalid();
        frames(sheet);
        return payload(Map.of("mode","margin-edit","action",action,"direction",direction,"seed",seed),"sheet.png",sheet);
    }
    public JsonNode seedIdle(String direction,byte[] approvedSeed,int seed) {
        if(!DIRECTIONS.contains(direction) || seed<0)throw AssetException.invalid();
        nativeFrame(approvedSeed);
        return payload(Map.of("mode","seed-idle","direction",direction,"seed",seed),"seed.png",approvedSeed);
    }
    /** Strict seed symmetry gate; a one-sided marking or different silhouette must not be copied. */
    static List<byte[]> mirroredMotion(byte[] sheet,byte[] sourceSeed,byte[] targetSeed) {
        var source=nativeFrame(sourceSeed);var target=nativeFrame(targetSeed);int visible=0,close=0;
        for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
            int a=source.getRGB(31-x,y),b=target.getRGB(x,y);
            if((a>>>24)!=(b>>>24))throw new AssetException(409,"MOTION_SEEDS_NOT_SYMMETRIC");
            if((a>>>24)==0)continue;
            visible++;int delta=0;
            for(int shift:List.of(0,8,16))delta=Math.max(delta,Math.abs(((a>>shift)&255)-((b>>shift)&255)));
            if(delta>64)throw new AssetException(409,"MOTION_SEEDS_NOT_SYMMETRIC");
            if(delta<=3)close++;
        }
        if(close*100<visible*95)throw new AssetException(409,"MOTION_SEEDS_NOT_SYMMETRIC");
        var frames=frames(sheet);sheet(frames,sourceSeed);var result=new ArrayList<byte[]>();
        for(byte[] bytes:frames) {
            var frame=nativeFrame(bytes);var mirror=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)mirror.setRGB(x,y,frame.getRGB(31-x,y));
            try {var out=new ByteArrayOutputStream();ImageIO.write(mirror,"png",out);result.add(out.toByteArray());}
            catch(IOException e){throw AssetException.unavailable();}
        }
        return result; // Raw evidence; never replace frame zero before the raw review.
    }
    private JsonNode payload(Object input,String imageName,byte[] image) {
        Path dir=null; Process process=null;
        try {
            dir=Files.createTempDirectory("styled-sprite-");
            for(String name:List.of("scripts/styled_dog/__init__.py","scripts/styled_dog/client.py","scripts/styled_dog/source.py",
                "scripts/styled_dog/pipeline.py","scripts/styled_dog/quality.py","scripts/styled_dog/tail_repair.py","scripts/styled_dog/server_bridge.py",
                "asset-styles/cozy32-v1/style.png","asset-styles/cozy32-v1/rules.json","asset-styles/cozy32-v1/quality-rules.json")) {
                Path file=dir.resolve(name);Files.createDirectories(file.getParent());
                try(var in=getClass().getResourceAsStream("/styled-pipeline/"+name)) {
                    if(in==null) throw new IOException("Missing pipeline");Files.copy(in,file);
                }
            }
            var work=dir.resolve("run");Files.createDirectories(work);
            Files.write(work.resolve(imageName),image);Files.write(work.resolve("input.json"),json.writeValueAsBytes(input));
            var builder=new ProcessBuilder(python,"-m","styled_dog.server_bridge",work.toString()).directory(dir.resolve("scripts").toFile());
            builder.environment().clear();builder.environment().put("LANG","C.UTF-8");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process=builder.start();
            if(!process.waitFor(45,TimeUnit.SECONDS)) throw new AssetException(503,"STYLED_PAYLOAD_TIMEOUT");
            if(process.exitValue()!=0) throw new AssetException(422,"STYLED_INPUT_REQUIRES_REVIEW");
            if(Files.size(work.resolve("payload.json"))>4_000_000) throw new IOException("Payload size");
            return json.readTree(Files.readAllBytes(work.resolve("payload.json")));
        } catch(AssetException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw AssetException.unavailable(); }
        catch(IOException e) { throw new AssetException(503,"STYLED_CODEC_UNAVAILABLE"); }
        finally {
            if(process!=null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly(); }
            if(dir!=null) try(var paths=Files.walk(dir)) { for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch(IOException ignored) { }
        }
    }
    static BufferedImage nativeFrame(byte[] bytes) {
        if(bytes==null || bytes.length>100_000 || !Arrays.equals(SpriteNormalizer.dimensions(bytes,32),new int[]{32,32}))
            throw new AssetException(422,"STYLED_FRAME_INVALID");
        try {
            var image=ImageIO.read(new ByteArrayInputStream(bytes));boolean visible=false,clear=false;
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) { int a=image.getRGB(x,y)>>>24;visible|=a!=0;clear|=a==0; }
            if(!visible || !clear) throw new AssetException(422,"STYLED_ALPHA_INVALID");
            return image;
        } catch(IOException e) { throw new AssetException(422,"STYLED_FRAME_INVALID"); }
    }
    static byte[] rawSheet(List<byte[]> frames) { return sheet(frames,frames.getFirst()); }
    static List<byte[]> restoreEditPalette(List<byte[]> frames,byte[] seed) {
        if(frames.size()!=9)throw new AssetException(422,"STYLED_FRAME_COUNT_INVALID");
        var original=nativeFrame(seed);var colors=new TreeSet<Integer>();
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((original.getRGB(x,y)>>>24)!=0)colors.add(original.getRGB(x,y)&0xffffff);
        var result=new ArrayList<byte[]>();result.add(seed);
        for(int i=1;i<9;i++) {
            var image=nativeFrame(frames.get(i));
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                int pixel=image.getRGB(x,y);if((pixel>>>24)==0)continue;
                int best=0,distance=Integer.MAX_VALUE;
                for(int color:colors) {
                    int r=((pixel>>16)&255)-((color>>16)&255),g=((pixel>>8)&255)-((color>>8)&255),b=(pixel&255)-(color&255);
                    int next=r*r+g*g+b*b;if(next<distance){distance=next;best=color;}
                }
                image.setRGB(x,y,(pixel&0xff000000)|best);
            }
            try {var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);result.add(out.toByteArray());}
            catch(IOException e){throw new AssetException(422,"STYLED_FRAME_INVALID");}
        }
        return result;
    }
    static byte[] sheet(List<byte[]> frames,byte[] seed) {
        if(frames.size()!=9) throw new AssetException(422,"STYLED_FRAME_COUNT_INVALID");
        var origin=nativeFrame(seed);var sheet=new BufferedImage(288,32,BufferedImage.TYPE_INT_ARGB);
        for(int i=0;i<9;i++) {
            var frame=nativeFrame(frames.get(i));
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                int pixel=frame.getRGB(x,y);
                if(i==0 && pixel!=origin.getRGB(x,y)) throw new AssetException(422,"STYLED_SOURCE_FRAME_CHANGED");
                sheet.setRGB(i*32+x,y,pixel);
            }
        }
        try { var out=new ByteArrayOutputStream();ImageIO.write(sheet,"png",out);return out.toByteArray(); }
        catch(IOException e) { throw AssetException.unavailable(); }
    }
    static List<byte[]> frames(byte[] sheet) {
        if(sheet==null || sheet.length>900_000 || !Arrays.equals(SpriteNormalizer.dimensions(sheet,288),new int[]{288,32}))throw new AssetException(422,"STYLED_SHEET_INVALID");
        try {
            var im=ImageIO.read(new ByteArrayInputStream(sheet));var frames=new ArrayList<byte[]>();
            for(int i=0;i<9;i++){var out=new ByteArrayOutputStream();ImageIO.write(im.getSubimage(i*32,0,32,32),"png",out);frames.add(out.toByteArray());}
            return frames;
        }catch(IOException e){throw new AssetException(422,"STYLED_SHEET_INVALID");}
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static JsonNode rules(JsonMapper json) {
        try(var in=StyledSpriteCodec.class.getResourceAsStream("/styled-pipeline/asset-styles/cozy32-v1/rules.json")) {
            return json.readTree(in.readAllBytes());
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    static byte[] qualityRulesBytes() {
        try(var in=StyledSpriteCodec.class.getResourceAsStream("/styled-pipeline/asset-styles/cozy32-v1/quality-rules.json")) {
            if(in==null)throw new IOException("Missing quality rules");return in.readAllBytes();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    static JsonNode qualityRules(JsonMapper json) {return json.readTree(qualityRulesBytes());}
    static String qualityRulesSha() {return sha(qualityRulesBytes());}
}
