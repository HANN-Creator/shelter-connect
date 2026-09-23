package org.shelterconnect.api.behavior;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.shelterconnect.api.auth.ShelterAccessService;
import org.shelterconnect.api.catalog.CatalogRepository;
import org.shelterconnect.api.management.ManagementRepository;
import tools.jackson.databind.JsonNode;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

@Service
public class BehaviorService {
	private final BehaviorRepository repository;
	private final ShelterAccessService access;
	private final CatalogRepository catalog;
	private final ManagementRepository dogs;
	public BehaviorService(BehaviorRepository repository,ShelterAccessService access,CatalogRepository catalog,ManagementRepository dogs) {
		this.repository=repository;this.access=access;this.catalog=catalog;this.dogs=dogs;
	}
	@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
	public Playback playback(String dog) {
		UUID id=BehaviorInput.id(dog);
		if(catalog.dog(id).isEmpty()) throw new BehaviorException(404,"DOG_NOT_FOUND","강아지를 찾을 수 없어요.");
		var profile=repository.profile(id).orElse(null);
		if(profile!=null && "CONFIRMED".equals(profile.status()) && profile.schemaVersion()==1
				&& repository.evidenceValid(id,profile.evidenceObservationIds(),false)) {
			try { return new Playback(id,1,"CONFIRMED",profile.revision(),BehaviorInput.settings(profile.settings())); }
			catch(BehaviorException ex) { /* Legacy or manually edited invalid settings must never become executable. */ }
		}
		return new Playback(id,1,"DEFAULT",null,BehaviorInput.defaults());
	}
	@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
	public Profile profile(UUID subject,String dog) {
		UUID id=BehaviorInput.id(dog);access.requireDog(subject,id);return repository.profile(id).orElse(null);
	}
	@Transactional(timeout=10)
	public Profile save(UUID subject,String dog,JsonNode body) {
		UUID id=BehaviorInput.id(dog);editable(subject,id);
		Save save=BehaviorInput.save(body);
		var old=repository.profile(id).orElse(null);
		if(save.expectedRevision()!=(old==null?0:old.revision())) throw BehaviorException.stale();
		if(!save.evidence().isEmpty() && !repository.evidenceValid(id,save.evidence(),true)) throw BehaviorException.evidence();
		repository.save(id,save);return repository.profile(id).orElseThrow();
	}
	@Transactional(timeout=10)
	public Profile confirm(UUID subject,String dog,JsonNode body) {
		UUID id=BehaviorInput.id(dog);UUID actor=editable(subject,id);
		int revision=BehaviorInput.confirmation(body);
		var profile=repository.profile(id).orElseThrow(()->new BehaviorException(404,"BEHAVIOR_NOT_FOUND","먼저 행동 설정을 저장해 주세요."));
		if(revision!=profile.revision()) throw BehaviorException.stale();
		if(profile.schemaVersion()!=1) throw BehaviorException.invalid();
		BehaviorInput.settings(profile.settings());
		if(!repository.evidenceValid(id,profile.evidenceObservationIds(),true)) throw BehaviorException.evidence();
		if(!"CONFIRMED".equals(profile.status())) repository.confirm(id,revision,actor);
		return repository.profile(id).orElseThrow();
	}
	/** Stable generation plan derived only from confirmed, evidenced observations. */
	public record AssetSelection(Integer revision,java.util.List<String> actions) {}
	@Transactional
	public AssetSelection assetSelection(UUID dog) {
		var selected=new java.util.ArrayList<String>(java.util.List.of("BASE","IDLE","WALK"));
		var profile=repository.lockedProfile(dog).orElse(null);
		if(profile==null || !"CONFIRMED".equals(profile.status()) || profile.schemaVersion()!=1
				|| !repository.evidenceValid(dog,profile.evidenceObservationIds(),true)) return new AssetSelection(null,java.util.List.copyOf(selected));
		try {
			var settings=BehaviorInput.settings(profile.settings());
			settings.actions().entrySet().stream().filter(e->e.getKey()!=Action.IDLE && e.getKey()!=Action.WALK && e.getValue().weight()>0)
				.sorted(java.util.Comparator.<java.util.Map.Entry<Action,Motion>>comparingInt(e->e.getValue().weight()).reversed().thenComparing(e->e.getKey().ordinal()))
				.limit(2).forEach(e->selected.add(e.getKey().name()));
			return new AssetSelection(profile.revision(),java.util.List.copyOf(selected));
		} catch(BehaviorException ignored) { return new AssetSelection(null,java.util.List.of("BASE","IDLE","WALK")); }
	}
	private UUID editable(UUID subject,UUID dog) {
		var writer=access.requireDogForWrite(subject,dog);
		var current=dogs.dog(dog,writer.shelterId()).orElseThrow(BehaviorException::stale);
		if(current.archivedAt()!=null) throw new BehaviorException(409,"DOG_ARCHIVED","보관된 강아지 설정은 수정할 수 없어요.");
		return writer.userId();
	}
}
