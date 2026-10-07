package org.shelterconnect.api.asset;

import org.springframework.stereotype.Component;
import org.shelterconnect.api.chat.AiFailure;
import tools.jackson.databind.JsonNode;

@Component
public class StyledLessonPromptComposer {
    private final StyledLessonPromptStore store;private final StyledLessonPromptAgent agent;
    public StyledLessonPromptComposer(StyledLessonPromptStore store,StyledLessonPromptAgent agent){this.store=store;this.agent=agent;}
    public String describe(StyledAssetStore.Work w,JsonNode lessons,String base,int limit) {
        int budget=limit-base.length()-StyledLessonPromptAgent.PREFIX.length();
        if(budget<15)throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_NO_SPACE");
        var entry=store.start(w,lessons,base,budget);
        if(entry.state().equals("READY")) {
            StyledLessonPromptAgent.checkPrompt(entry.draft(),budget);StyledLessonPromptAgent.checkVerdict(entry.validation(),lessons.size());
            return base+StyledLessonPromptAgent.PREFIX+entry.draft().path("prompt").asText();
        }
        if(!entry.created())throw StyledLessonPromptAgent.invalid(entry.state().equals("FAILED")?"LESSON_PROMPT_REVIEW_REQUIRED":"LESSON_PROMPT_INTERRUPTED");
        try {
            var draft=agent.compose(lessons,base,budget);
            for(int attempt=0;attempt<3;attempt++) {
                StyledLessonPromptAgent.checkPrompt(draft,budget);store.draft(w,lessons,entry.key(),draft);
                var verdict=agent.verify(lessons,base,draft,budget);store.verdict(w,lessons,entry.key(),verdict);
                try {StyledLessonPromptAgent.checkVerdict(verdict,lessons.size());}
                catch(AssetException rejected) {
                    if(attempt==2)throw rejected;
                    draft=agent.rewrite(lessons,base,budget,draft,verdict);continue;
                }
                String description=base+StyledLessonPromptAgent.PREFIX+draft.path("prompt").asText();
                store.ready(w,lessons,entry.key(),verdict,description);return description;
            }
            throw StyledLessonPromptAgent.invalid("LESSON_PROMPT_MEANING_LOST");
        } catch(RuntimeException e) {
            String code=e instanceof AssetException a?a.code:e instanceof AiFailure a?"LESSON_PROMPT_"+a.code():"LESSON_PROMPT_INTERRUPTED";
            store.failed(w,entry.key(),code);throw StyledLessonPromptAgent.invalid(code);
        }
    }
}
