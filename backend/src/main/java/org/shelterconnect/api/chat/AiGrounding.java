package org.shelterconnect.api.chat;

import java.util.HashSet;
import java.util.Optional;
import org.springframework.stereotype.Component;
import static org.shelterconnect.api.chat.AiTypes.*;

@Component
public class AiGrounding {
	public Optional<Generated> local(Context context) {
		if(context.question().strip().matches("(?i)(안녕|안녕하세요|반가워|하이|hello|hi)[!.?~\\s]*"))
			return Optional.of(new Generated("안녕! 나랑 천천히 친해져 보자. 어떤 게 궁금해?",false,java.util.List.of(),null));
		return context.observations().isEmpty()?Optional.of(AiTypes.unknown()):Optional.empty();
	}
	public Generated check(Context context,Generated answer) {
		if(answer==null || answer.text()==null || answer.text().isBlank() || answer.text().indexOf('\0')>=0
				|| answer.text().codePointCount(0,answer.text().length())>1000 || answer.needsShelterConfirmation()
				|| answer.observationIds()==null || answer.observationIds().isEmpty() || answer.observationIds().size()>8)
			return AiTypes.unknown();
		var allowed=new HashSet<>(context.observations().stream().map(Observation::id).toList());
		var cited=new HashSet<>(answer.observationIds());
		if(cited.size()!=answer.observationIds().size() || !allowed.containsAll(cited)) return AiTypes.unknown();
		return answer;
	}
}
