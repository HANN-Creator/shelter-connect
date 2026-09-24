package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A bounded local renderer. The subprocess receives image/profile files, never credentials. */
@Component
public final class PythonMotionHarness implements MotionHarness {
    private final JsonMapper json;
    private final String python;
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(1);
    public PythonMotionHarness(JsonMapper json,@Value("${app.assets.harness-python:python3}") String python) {
        this.json=json;this.python=python;
    }
    public JsonNode propose(byte[] base) { return execute(base,Map.of("mode","fit")).metadata().path("profile"); }
    public Clip render(AssetAction action,byte[] base,JsonNode profile) {
        if(!MotionHarness.handles(action)) throw AssetException.invalid();
        var result=execute(base,Map.of("mode","render","action",action.name(),"profile",profile));
        JsonNode m=result.metadata();
        int count=m.path("frameCount").asInt(),duration=m.path("durationMs").asInt();
        int expected=action==AssetAction.BACK_OFF?24:48;
        if(count!=expected || duration<1 || duration>1000 || result.sheet().length==0) throw new AssetException(422,"HARNESS_INVALID_RESULT");
        var size=SpriteNormalizer.dimensions(result.sheet(),3072);
        if(size[0]!=64*count || size[1]!=64) throw new AssetException(422,"HARNESS_INVALID_RESULT");
        return new Clip(result.sheet(),count,duration,Map.of("templateVersion",m.path("templateVersion").asText(),"paletteChecked",true,"boundsChecked",true));
    }
    private record Output(JsonNode metadata,byte[] sheet) {}
    private Output execute(byte[] base,Object request) {
        SpriteNormalizer.dimensions(base,64);
        if(!capacity.tryAcquire()) throw new AssetException(503,"HARNESS_BUSY");
        Path dir=null;Process process=null;
        try {
            dir=Files.createTempDirectory("shelter-motion-");
            for(String name:List.of("runner.py","render.py","outline.py","limb_art.py","motion-templates.json","canonical-profile.json")) {
                try(var in=getClass().getResourceAsStream("/motion-harness/"+name)) {
                    if(in==null) throw new IOException("Missing bundled renderer");
                    Files.copy(in,dir.resolve(name));
                }
            }
            Files.write(dir.resolve("base.png"),base);
            Files.write(dir.resolve("request.json"),json.writeValueAsBytes(request));
            var builder=new ProcessBuilder(python,dir.resolve("runner.py").toString(),dir.toString());
            builder.directory(dir.toFile());builder.environment().clear();
            builder.environment().put("LANG","C.UTF-8");builder.environment().put("OPENBLAS_NUM_THREADS","1");
            builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process=builder.start();
            if(!process.waitFor(60,TimeUnit.SECONDS)) throw new AssetException(503,"HARNESS_TIMEOUT");
            if(process.exitValue()!=0) throw new AssetException(422,"RIG_PROFILE_REQUIRES_REVIEW");
            var response=dir.resolve("response.json");
            if(Files.size(response)>32768) throw new IOException("Invalid renderer response");
            byte[] sheet=new byte[0];
            if(Files.exists(dir.resolve("sheet.png"))) {
                if(Files.size(dir.resolve("sheet.png"))>512000) throw new IOException("Oversized sprite sheet");
                sheet=Files.readAllBytes(dir.resolve("sheet.png"));
            }
            return new Output(json.readTree(Files.readAllBytes(response)),sheet);
        } catch(AssetException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssetException(503,"HARNESS_INTERRUPTED"); }
        catch(IOException e) { throw new AssetException(503,"HARNESS_UNAVAILABLE"); }
        finally {
            if(process!=null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly(); }
            if(dir!=null) try(var paths=Files.walk(dir)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } catch(IOException ignored) { }
            capacity.release();
        }
    }
}
