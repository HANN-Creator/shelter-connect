package org.shelterconnect.api.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.AccountService;
import static org.shelterconnect.api.chat.AiTypes.*;
import static org.shelterconnect.api.chat.ChatResponses.*;

/** Short database transactions only. Never call a model while holding these locks. */
@Service
@Transactional(timeout=10)
public class AiReplyStore {
	private final AccountService accounts;
	private final ChatRepository chats;
	private final AiReplyRepository replies;
	private final AiProperties properties;
	public AiReplyStore(AccountService accounts,ChatRepository chats,AiReplyRepository replies,AiProperties properties) {
		this.accounts=accounts;this.chats=chats;this.replies=replies;this.properties=properties;
	}
	public Started start(UUID subject,UUID sessionId,UUID requestId,boolean retry) {
		UUID user=accounts.lockProfile(subject,false).id();
		Session session=owned(user,sessionId);
		Request request=request(sessionId,requestId);
		var cached=replies.reply(requestId);
		if(cached.isPresent()) return new Started(null,completed(requestId,cached.get(),200));
		if(!properties.enabled()) throw new ChatException(503,"AI_NOT_CONFIGURED","아직 대화 답변을 준비 중이에요. 잠시 후 다시 확인해 주세요.");
		replies.expire(sessionId);
		request=request(sessionId,requestId);
		if(request.active()) return new Started(null,outcome(request,null,202));
		if(request.message().processingStatus().equals("FAILED") && (!retry || request.attempts()>=3)) return new Started(null,outcome(request,null,200));
		if(request.message().processingStatus().equals("COMPLETED")) throw ChatException.conflict("REPLY_UNAVAILABLE","저장된 답변을 확인할 수 없어요. 관리자에게 문의해 주세요.");
		if(!session.status().equals("OPEN")) throw ChatException.conflict("SESSION_CLOSED","종료된 대화방이에요.");
		if(!chats.lockAvailableDog(session.dogId())) throw ChatException.conflict("DOG_UNAVAILABLE","지금은 새 대화를 나눌 수 없어요.");
		if(replies.otherActive(sessionId,requestId)) throw ChatException.conflict("REPLY_IN_PROGRESS","앞선 답변이 끝난 뒤 다시 요청해 주세요.");
		UUID token=UUID.randomUUID();
		replies.claim(requestId,token,properties);
		return new Started(new Work(subject,user,sessionId,session.dogId(),requestId,token,replies.context(session,request.message())),null);
	}
	public Outcome complete(Work work,Generated answer) {
		accounts.lockProfile(work.subject(),false);
		Session session=owned(work.userId(),work.sessionId());
		Request request=request(work.sessionId(),work.requestId());
		var cached=replies.reply(work.requestId());
		if(cached.isPresent()) return completed(work.requestId(),cached.get(),200);
		if(!work.token().equals(request.token())) return outcome(request,null,request.active()?202:200);
		if(!request.active()) { replies.fail(work,"GENERATION_EXPIRED");return outcome(request(work.sessionId(),work.requestId()),null,200); }
		if(!session.status().equals("OPEN") || !chats.lockAvailableDog(work.dogId())) {
			replies.fail(work,"CONTEXT_UNAVAILABLE");return outcome(request(work.sessionId(),work.requestId()),null,200);
		}
		var citedIds=answer.observationIds();
		List<Observation> used=work.context().observations().stream().filter(o->citedIds.contains(o.id())).toList();
		if(!replies.stillConfirmed(work.dogId(),used)) { answer=AiTypes.unknown();used=List.of(); }
		return completed(work.requestId(),replies.complete(work,answer,used),201);
	}
	public void abandon(Work work,String code) { replies.fail(work,code); }
	public Outcome current(UUID subject,UUID sessionId,UUID requestId) {
		UUID user=accounts.lockProfile(subject,false).id();owned(user,sessionId);
		Request request=request(sessionId,requestId);
		return outcome(request,replies.reply(requestId).orElse(null),request.active()?202:200);
	}
	private Session owned(UUID user,UUID session) { return chats.session(user,session,true).orElseThrow(ChatException::missing); }
	private Request request(UUID session,UUID message) { return replies.request(session,message).orElseThrow(()->new ChatException(404,"MESSAGE_NOT_FOUND","이 대화방의 사용자 메시지를 찾을 수 없어요.")); }
	private Outcome outcome(Request request,Message reply,int status) {
		if(reply!=null) return completed(request.message().id(),reply,200);
		String failure=request.message().failureCode();
		boolean retryable="FAILED".equals(request.message().processingStatus()) && request.attempts()<3;
		return new Outcome(new Reply(request.message().id(),request.message().processingStatus(),failure,retryable,null),status);
	}
	private Outcome completed(UUID request,Message reply,int status) { return new Outcome(new Reply(request,"COMPLETED",null,false,reply),status); }
}
