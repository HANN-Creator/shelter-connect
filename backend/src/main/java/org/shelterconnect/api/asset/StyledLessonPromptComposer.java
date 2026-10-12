package org.shelterconnect.api.asset;

import org.springframework.stereotype.Component;
import org.shelterconnect.api.chat.AiFailure;
import tools.jackson.databind.JsonNode;

@Component
public class StyledLessonPromptComposer {
    private final StyledLessonPromptStore store;private final StyledLessonPromptAgent agent;
    public StyledLessonPromptComposer(StyledLessonPromptStore store,StyledLessonPromptAgent agent){this.store=store;this.agent=agent;}
    public JsonNode warnings(StyledAssetStore.Work w){return store.warnings(w);}
    public String describe(StyledAssetStore.Work w,JsonNode lessons,String base,int limit) {
        if(w!=null && StyledVisualWarnings.enabled(w.qualityPolicy()))return advisory(w,lessons,base,limit);
        int budget=limit;
        if(budget<15)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_NO_SPACE");
        var entry=store.start(w,lessons,base,budget);
        if(entry.state().equals("READY")) {
            StyledLessonPromptAgent.checkPrompt(entry.draft(),budget);StyledLessonPromptAgent.checkVerdict(entry.validation(),lessons.size());
            return entry.draft().path("prompt").asText();
        }
        if(!entry.created())throw StyledLessonPromptAgent.invalid(entry.state().equals("FAILED")?"LESSON_PROMPT_REVIEW_REQUIRED":"LESSON_PROMPT_INTERRUPTED");
        try {
            var draft=write(w,lessons,entry.key(),budget,()->agent.compose(lessons,base,budget));
            for(int attempt=0;attempt<3;attempt++) {
                StyledLessonPromptAgent.checkPrompt(draft,budget);store.draft(w,lessons,entry.key(),draft);
                var verdict=agent.verify(lessons,base,draft,budget);store.verdict(w,lessons,entry.key(),verdict);
                try {StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());}
                catch(AssetException rejected) {
                    if(attempt==2)throw rejected;
                    var rejectedDraft=draft;
                    draft=write(w,lessons,entry.key(),budget,()->agent.rewrite(lessons,base,budget,rejectedDraft,verdict));continue;
                }
                String description=draft.path("prompt").asText();
                store.ready(w,lessons,entry.key(),verdict,description);return description;
            }
            throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_MEANING_LOST");
        } catch(RuntimeException e) {
            String code=e instanceof AssetException a?a.code:e instanceof AiFailure a?"LESSON_PROMPT_"+a.code():"LESSON_PROMPT_INTERRUPTED";
            if(e instanceof AssetException a && a.diagnostics!=null)store.diagnostics(w,lessons,entry.key(),a.diagnostics);
            store.failed(w,entry.key(),code);throw StyledLessonPromptAgent.invalid(code);
        }
    }
    private String advisory(StyledAssetStore.Work w,JsonNode lessons,String base,int budget) {
        var entry=store.start(w,lessons,base,budget);
        if(entry.state().equals("READY")){StyledLessonPromptAgent.checkPrompt(entry.draft(),budget);return entry.draft().path("prompt").asText();}
        if(!entry.created())throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_REVIEW_REQUIRED");
        try {
            var draft=write(w,lessons,entry.key(),budget,()->agent.compose(lessons,base,budget));
            StyledLessonPromptAgent.checkPrompt(draft,budget);
            store.draft(w,lessons,entry.key(),draft);
            var mapper=new tools.jackson.databind.json.JsonMapper();
            var audit=mapper.createObjectNode().put("enforcement","WARN_ONLY");
            try {
                var verdict=agent.verify(lessons,base,draft,budget);audit.set("observation",verdict);
                try{StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());}
                catch(AssetException uncertain){audit.put("warning",uncertain.code);}
            } catch(AiFailure | AssetException unavailable){audit.put("warning","PROMPT_REVIEW_UNAVAILABLE");}
            store.verdict(w,lessons,entry.key(),audit);store.ready(w,lessons,entry.key(),audit,draft.path("prompt").asText());
            return draft.path("prompt").asText();
        } catch(RuntimeException failure){
            String code=failure instanceof AssetException a?a.code:"LESSON_PROMPT_UNAVAILABLE";
            if(failure instanceof AssetException a && a.diagnostics!=null)store.diagnostics(w,lessons,entry.key(),a.diagnostics);
            store.failed(w,entry.key(),code);throw StyledLessonPromptAgent.invalid(code);
        }
    }
    private JsonNode write(StyledAssetStore.Work w,JsonNode lessons,String key,int budget,java.util.function.Supplier<JsonNode> writer) {
        try {return writer.get();}
        catch(AssetException failure) {
            var recovered=StyledPromptSpacingRecovery.recover(failure,budget);
            if(recovered.isEmpty())throw failure;
            var result=recovered.get();store.diagnostics(w,lessons,key,result.diagnostics());return result.draft();
        }
    }
}
