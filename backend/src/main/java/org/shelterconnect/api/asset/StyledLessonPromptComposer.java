package org.shelterconnect.api.asset;

import org.springframework.stereotype.Component;
import org.shelterconnect.api.chat.AiFailure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StyledLessonPromptComposer {
    private final StyledLessonPromptStore store;private final StyledLessonPromptAgent agent;
    private final JsonMapper json=new JsonMapper();
    public StyledLessonPromptComposer(StyledLessonPromptStore store,StyledLessonPromptAgent agent){this.store=store;this.agent=agent;}
    public JsonNode warnings(StyledAssetStore.Work w){return store.warnings(w);}
    public String describe(StyledAssetStore.Work w,JsonNode lessons,String base,int budget) {
        boolean advisory=w!=null && StyledVisualWarnings.enabled(w.qualityPolicy());
        // One cache/checkpoint lifecycle for both policies; only semantic enforcement differs.
        if(!advisory && budget<15)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_NO_SPACE");
        var entry=store.start(w,lessons,base,budget);
        if(entry.state().equals("READY")) {
            StyledLessonPromptAgent.checkPrompt(entry.draft(),budget);
            if(!advisory)StyledLessonPromptAgent.checkVerdict(entry.validation(),lessons.size());
            return entry.draft().path("prompt").asText();
        }
        if(!entry.created())throw StyledLessonPromptAgent.invalid(advisory || entry.state().equals("FAILED")
            ?"LESSON_PROMPT_REVIEW_REQUIRED":"LESSON_PROMPT_INTERRUPTED");
        try {
            var draft=write(w,lessons,base,entry.key(),budget,()->agent.compose(lessons,base,budget));
            int attempts=advisory?1:3;
            for(int attempt=0;attempt<attempts;attempt++) {
                StyledLessonPromptAgent.checkPrompt(draft,budget);store.draft(w,lessons,entry.key(),draft);
                var verdict=advisory?observe(lessons,base,draft,budget):agent.verify(lessons,base,draft,budget);
                store.verdict(w,lessons,entry.key(),verdict);
                if(!advisory) {
                    try {StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());}
                    catch(AssetException rejected) {
                        if(attempt==attempts-1)throw rejected;
                        var rejectedDraft=draft;
                        draft=write(w,lessons,base,entry.key(),budget,()->agent.rewrite(lessons,base,budget,rejectedDraft,verdict));continue;
                    }
                }
                String description=draft.path("prompt").asText();
                store.ready(w,lessons,entry.key(),verdict,description);return description;
            }
            throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_MEANING_LOST");
        } catch(RuntimeException failure) {
            String code=failure instanceof AssetException a?a.code:advisory?"LESSON_PROMPT_UNAVAILABLE"
                :failure instanceof AiFailure a?"LESSON_PROMPT_"+a.code():"LESSON_PROMPT_INTERRUPTED";
            if(failure instanceof AssetException a && a.diagnostics!=null)store.diagnostics(w,lessons,entry.key(),a.diagnostics);
            store.failed(w,entry.key(),code);throw StyledLessonPromptAgent.invalid(code);
        }
    }
    private JsonNode observe(JsonNode lessons,String base,JsonNode draft,int budget) {
        var audit=json.createObjectNode().put("enforcement","WARN_ONLY");
        try {
            var verdict=agent.verify(lessons,base,draft,budget);audit.set("observation",verdict);
            try{StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());}
            catch(AssetException uncertain){audit.put("warning",uncertain.code);}
        } catch(AiFailure | AssetException unavailable){audit.put("warning","PROMPT_REVIEW_UNAVAILABLE");}
        return audit;
    }
    private JsonNode write(StyledAssetStore.Work w,JsonNode lessons,String base,String key,int budget,java.util.function.Supplier<JsonNode> writer) {
        try {return writer.get();}
        catch(AssetException failure) {
            var recovered=StyledPromptSpacingRecovery.recover(failure,budget);
            if(recovered.isEmpty())recovered=StyledPromptWordingRecovery.recover(failure,budget);
            if(recovered.isEmpty() && StyledPromptEditRecovery.candidate(failure,budget).isPresent()) {
                store.prepareLengthEditing(w,lessons,key);
                recovered=agent.compact(lessons,base,budget,failure);
            }
            if(recovered.isEmpty())throw failure;
            var result=recovered.get();store.diagnostics(w,lessons,key,result.diagnostics());return result.draft();
        }
    }
}
