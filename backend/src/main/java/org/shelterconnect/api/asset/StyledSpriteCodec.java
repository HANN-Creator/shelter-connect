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
        if(!sha(photo).equals(traits.path("sourcePhotoSha256").asText())) throw new AssetException(409,"SOURCE_PHOTO_CHANGED");
        return payload(Map.of("mode","character","dogId",dog,"traits",traits),"photo-1.png",photo);
    }
    public JsonNode motion(JsonNode traits,String action,String direction,byte[] seed) {
        return motion(traits,action,direction,seed,json.createObjectNode());
    }
    public JsonNode motion(JsonNode traits,String action,String direction,byte[] seed,JsonNode quality) {
        nativeFrame(seed);
        if(!ACTIONS.contains(action) || !DIRECTIONS.contains(direction)) throw AssetException.invalid();
        return payload(Map.of("mode","motion","traits",traits,"action",action,"direction",direction,"quality",quality),"seed.png",seed);
    }
    private JsonNode payload(Object input,String imageName,byte[] image) {
        Path dir=null; Process process=null;
        try {
            dir=Files.createTempDirectory("styled-sprite-");
            for(String name:List.of("scripts/styled_dog/__init__.py","scripts/styled_dog/client.py","scripts/styled_dog/source.py",
                "scripts/styled_dog/pipeline.py","scripts/styled_dog/quality.py","scripts/styled_dog/server_bridge.py",
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
