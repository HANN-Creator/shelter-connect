package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.chat.AiProperties;
import tools.jackson.databind.JsonNode;

@Component
public class StyledLessonWorker {
    private final StyledLessonStore store;private final StyledLessonAgent agent;private final AssetStorage storage;
    private final AssetProperties assets;private final AiProperties ai;
    public StyledLessonWorker(StyledLessonStore store,StyledLessonAgent agent,AssetStorage storage,AssetProperties assets,AiProperties ai){
        this.store=store;this.agent=agent;this.storage=storage;this.assets=assets;this.ai=ai;
    }
    public void tick() {
        if(!assets.enabled || !ai.enabled())return;
        var work=store.claim();if(work==null)return;
        try {
            if(!store.authorized(work)){store.failed(work,"LESSON_EVIDENCE_REVOKED");return;}
            var cases=new ArrayList<StyledLessonAgent.Case>();
            for(int i=0;i<work.examples().size();i++) {
                var example=work.examples().get(i);var seeds=new ArrayList<byte[]>();
                for(String direction:StyledSpriteCodec.DIRECTIONS)seeds.add(load(example.seeds().at("/keys/"+direction).asText(),example.seeds().at("/hashes/"+direction).asText()));
                if(example.action().equals("BASE")) {
                    if(!StyledSeedQualityAgent.binding(seeds).equals(example.inputSha256()))throw new AssetException(409,"LESSON_EVIDENCE_CHANGED");
                    var ref=example.seeds().path("photo");
                    byte[] photo=storage.photo(UUID.fromString(ref.path("dogId").asText()),ref.path("bucket").asText(),ref.path("key").asText());
                    if(!StyledSpriteCodec.sha(photo).equals(ref.path("sha256").asText()))throw new AssetException(409,"LESSON_EVIDENCE_CHANGED");
                    cases.add(new StyledLessonAgent.Case("CASE_"+i,example.report(),seeds,List.of(),photo));
                } else {
                    var sheet=load(example.result().path("key").asText(),example.inputSha256());
                    cases.add(new StyledLessonAgent.Case("CASE_"+i,example.report(),seeds,StyledSpriteCodec.frames(sheet)));
                }
            }
            if(!store.authorized(work)){store.failed(work,"LESSON_EVIDENCE_REVOKED");return;}
            if(work.status().equals("PROPOSING")) {
                int source=0;for(int i=0;i<work.examples().size();i++)if(work.examples().get(i).id().toString().equals(work.scope().path("sourceExampleId").asText()))source=i;
                store.proposed(work,work.scope().path("action").asText().equals("BASE")?
                    agent.proposeSeeds(work.scope(),cases.get(source)):agent.propose(work.scope(),cases.get(source)));
            } else store.validated(work,work.scope().path("action").asText().equals("BASE")?
                agent.replaySeeds(work.scope(),work.candidate(),cases):agent.replay(work.scope(),work.candidate(),cases));
        }catch(RuntimeException error){store.failed(work,error instanceof AssetException e?e.code:"LESSON_MODEL_OR_STORAGE_FAILED");}
    }
    private byte[] load(String key,String sha) {
        byte[] image=storage.asset(key);
        if(!StyledSpriteCodec.sha(image).equals(sha))throw new AssetException(409,"LESSON_EVIDENCE_CHANGED");return image;
    }
}
