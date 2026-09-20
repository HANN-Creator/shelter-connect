package org.shelterconnect.api.chat;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.shelterconnect.api.auth.AccountAccessException;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.chat.AiTypes.*;

@Service
public class AiReplyService {
	private final AiReplyStore store;
	private final AiProvider provider;
	private final AiGrounding grounding;
	public AiReplyService(AiReplyStore store,AiProvider provider,AiGrounding grounding) { this.store=store;this.provider=provider;this.grounding=grounding; }
	// Deliberately not transactional: the provider call must not consume a DB connection/lock.
	public Outcome reply(UUID subject,String sessionId,String messageId,JsonNode body) {
		UUID session=ChatInput.id(sessionId),message=ChatInput.id(messageId);
		boolean retry=false;
		if(body!=null) {
			if(!body.isObject() || body.size()>1 || (body.size()==1 && (!body.has("retry") || !body.get("retry").isBoolean()))) throw ChatException.invalid();
			retry=body.path("retry").asBoolean(false);
		}
		Started started=store.start(subject,session,message,retry);
		if(started.existing()!=null) return started.existing();
		Work work=started.work();
		try {
			Generated answer=grounding.local(work.context()).orElseGet(()->grounding.check(work.context(),provider.generate(work.context())));
			return store.complete(work,answer);
		} catch(AiFailure failure) {
			store.abandon(work,failure.code());
			return store.current(subject,session,message);
		} catch(AccountAccessException denied) {
			store.abandon(work,"ACCOUNT_UNAVAILABLE");throw denied;
		} catch(RuntimeException failure) {
			store.abandon(work,"AI_INTERNAL_ERROR");
			throw new ChatException(500,"INTERNAL_ERROR","답변을 저장하지 못했어요. 메시지 상태를 확인한 뒤 다시 시도해 주세요.");
		}
	}
}
