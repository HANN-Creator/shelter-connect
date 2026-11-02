package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** One lossless, content-addressed PNG for every direction and action of a styled dog. */
final class StyledSpriteAtlas {
    record Atlas(String key, int width, int height, Map<String,Integer> rows, String sha256, String sourceFingerprint) {
        int y(String label) { return Objects.requireNonNull(rows.get(label)); }
    }
    private record Source(String label, String key, String sha256, int width, int height) {}
    private final AssetStorage storage;
    private final Map<String,Atlas> ready = new ConcurrentHashMap<>();

    StyledSpriteAtlas(AssetStorage storage) { this.storage=storage; }

    Atlas ensure(StyledAssetStore.Job job, int motionSize) {
        if (!job.complete()) throw new AssetException(409,"ASSET_NOT_READY");
        var sources = sources(job,motionSize);
        String sourceHash = sha256(sources.stream().map(s -> s.label()+"\n"+s.key()+"\n"+s.sha256()+"\n").reduce("atlas-v1\n",String::concat)
            .getBytes(StandardCharsets.UTF_8));
        String key = job.dogId()+"/"+job.id()+"/native-32/atlases/"+sourceHash+".png";
        return ready.computeIfAbsent(key, ignored -> create(key, sourceHash, sources));
    }

    private static List<Source> sources(StyledAssetStore.Job job,int motionSize) {
        String prefix=job.dogId()+"/"+job.id()+"/native-32/";
        var result=new ArrayList<Source>();var seed=job.steps().getFirst().result();
        for(String direction:StyledSpriteCodec.DIRECTIONS)
            result.add(source("BASE:"+direction,seed.at("/keys/"+direction).asText(),seed.at("/hashes/"+direction).asText(),32,32,prefix+"directions/"));
        for(var step:job.steps().subList(1,job.steps().size()))
            result.add(source(step.label(),step.result().path("key").asText(),step.result().path("sha256").asText(),motionSize*9,motionSize,prefix+"sheets/"));
        return List.copyOf(result);
    }

    private static Source source(String label,String key,String hash,int width,int height,String prefix) {
        if(!key.startsWith(prefix) || key.contains("..") || !hash.matches("[a-f0-9]{64}"))
            throw new AssetException(409,"STYLED_ASSET_KEY_INVALID");
        return new Source(label,key,hash,width,height);
    }

    private Atlas create(String key,String sourceHash,List<Source> sources) {
        int width=sources.stream().mapToInt(Source::width).max().orElseThrow();
        int height=sources.stream().mapToInt(Source::height).sum();
        if(width>4096 || height>4096) throw new AssetException(409,"STYLED_ATLAS_DIMENSIONS_INVALID");
        var rows=new LinkedHashMap<String,Integer>();int y=0;
        for(var source:sources) { rows.put(source.label(),y);y+=source.height(); }
        try {
            byte[] existing=storage.asset(key);
            if(existing!=null) {
                int[] size=SpriteNormalizer.dimensions(existing,4096);
                if(size[0]==width && size[1]==height)
                    return new Atlas(key,width,height,Map.copyOf(rows),sha256(existing),sourceHash);
            }
        } catch(AssetException ignored) { /* A missing persistent atlas is built from the saved source strips. */ }
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(var source:sources) {
            byte[] original=storage.asset(source.key());
            if(original==null)throw AssetException.unavailable();
            if(!sha256(original).equals(source.sha256())) throw new AssetException(409,"STYLED_ATLAS_SOURCE_CHANGED");
            int[] size=SpriteNormalizer.dimensions(original,4096);
            if(size[0]!=source.width() || size[1]!=source.height()) throw new AssetException(409,"STYLED_ATLAS_SOURCE_INVALID");
            try {
                var strip=ImageIO.read(new ByteArrayInputStream(original));
                if(strip==null)throw new AssetException(409,"STYLED_ATLAS_SOURCE_INVALID");
                int top=rows.get(source.label());
                for(int row=0;row<source.height();row++)for(int col=0;col<source.width();col++)
                    image.setRGB(col,top+row,strip.getRGB(col,row));
            } catch(java.io.IOException e) { throw new AssetException(409,"STYLED_ATLAS_SOURCE_INVALID"); }
        }
        byte[] png=StyledSpriteCodec.png(image);
        if(png.length>256*1024) throw new AssetException(409,"STYLED_ATLAS_TOO_LARGE");
        storage.put(key,png);
        return new Atlas(key,width,height,Map.copyOf(rows),sha256(png),sourceHash);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
