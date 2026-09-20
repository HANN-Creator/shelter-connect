package org.shelterconnect.api.chat;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.AccountService;
import org.shelterconnect.api.catalog.CatalogResponses.Page;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.chat.ChatResponses.*;

@Service
@Transactional(readOnly=true)
public class ChatService {
	private final AccountService accounts;
	private final ChatRepository repository;
	public ChatService(AccountService accounts,ChatRepository repository) { this.accounts=accounts; this.repository=repository; }

	@Transactional(timeout=10)
	public Stored<Session> open(UUID subject,String dogId,JsonNode body) {
		UUID dog=ChatInput.id(dogId);
		ChatInput.emptyBody(body);
		// Serialize this user's open/resume requests, including when no session exists yet.
		UUID user=accounts.lockProfile(subject,true).id();
		if(!repository.lockAvailableDog(dog)) throw new ChatException(404,"DOG_NOT_FOUND","대화할 수 있는 강아지를 찾을 수 없어요.");
		var current=repository.openSession(user,dog);
		return current.map(value->new Stored<>(value,false)).orElseGet(()->new Stored<>(repository.createSession(user,dog),true));
	}
	public Session session(UUID subject,String sessionId) {
		UUID id=ChatInput.id(sessionId), user=accounts.profile(subject).id();
		return owned(user,id,false);
	}
	public Page<Session> sessions(UUID subject,String dogId,String cursor,String limit) {
		UUID dog=dogId==null?null:ChatInput.id(dogId), user=accounts.profile(subject).id();
		int count=ChatInput.limit(limit);
		String scope="sessions:"+user+":"+(dog==null?"all":dog);
		return page(repository.sessions(user,dog,ChatInput.cursor(cursor,scope),count+1),count,
				s->ChatInput.cursor(scope,s.createdAt(),s.id()));
	}
	public Page<Message> messages(UUID subject,String sessionId,String cursor,String limit) {
		UUID id=ChatInput.id(sessionId), user=accounts.profile(subject).id();
		owned(user,id,false);
		int count=ChatInput.limit(limit);
		String scope="messages:"+user+":"+id;
		return page(repository.messages(user,id,ChatInput.cursor(cursor,scope),count+1),count,
				m->ChatInput.cursor(scope,m.createdAt(),m.id()));
	}
	@Transactional(timeout=10)
	public Stored<Message> send(UUID subject,String sessionId,JsonNode body) {
		UUID id=ChatInput.id(sessionId);
		var input=ChatInput.send(body);
		UUID user=accounts.lockProfile(subject,false).id();
		Session session=owned(user,id,true);
		var old=repository.messageByKey(id,input.clientMessageId());
		if(old.isPresent()) {
			if(!old.get().text().equals(input.text())) throw ChatException.conflict("MESSAGE_ID_CONFLICT","같은 전송 ID에 다른 내용이 있어요. 새 메시지는 새 ID로 보내 주세요.");
			return new Stored<>(old.get(),false);
		}
		if(!session.status().equals("OPEN")) throw ChatException.conflict("SESSION_CLOSED","종료된 대화방에는 새 메시지를 보낼 수 없어요.");
		if(!repository.lockAvailableDog(session.dogId())) throw ChatException.conflict("DOG_UNAVAILABLE","지금은 새 대화를 나눌 수 없어요. 이전 기록은 계속 볼 수 있어요.");
		return new Stored<>(repository.createMessage(session,input),true);
	}
	private Session owned(UUID user,UUID id,boolean lock) { return repository.session(user,id,lock).orElseThrow(ChatException::missing); }
	private static <T> Page<T> page(List<T> rows,int count,Function<T,String> cursor) {
		var data=List.copyOf(rows.subList(0,Math.min(count,rows.size())));
		return new Page<>(data,rows.size()>count?cursor.apply(data.getLast()):null);
	}
}
