package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AssetWorker {
    private final AssetStore store;
    private final AssetProvider provider;
    private final AssetStorage storage;
    private final AssetProperties properties;
    private final MotionHarness harness;
    private final PhotoAppearanceProvider appearance;
    public AssetWorker(AssetStore store,AssetProvider provider,AssetStorage storage,AssetProperties properties,MotionHarness harness,PhotoAppearanceProvider appearance) {
        this.store=store;this.provider=provider;this.storage=storage;this.properties=properties;this.harness=harness;
        this.appearance=appearance;
    }
    // Each tick advances one durable step. All network calls run outside DB transactions.
    public void tick() {
        if(!properties.enabled) return;
        var work=store.claim();if(work==null) return;
        boolean reserved=false;
        try {
            if(MotionHarness.handles(work.action()) && java.util.Set.of("PENDING","RENDERING").contains(work.stepStatus())) {
                var profile=store.localProfile(work);if(profile==null) return;
                storage.ready();
                byte[] base=storage.asset(work.prefix()+"base.png");
                if(!store.reserveLocal(work)) return;
                var clip=harness.render(work.action(),base,profile);
                if(!store.authorized(work)) return;
                String key=work.prefix()+work.action().name().toLowerCase(java.util.Locale.ROOT)+".png";
                storage.put(key,clip.sheet());
                store.success(work,Map.of("key",key,"frameCount",clip.frameCount(),"width",64,"height",64,
                    "durationMs",clip.durationMs(),"loop",true,"holdLastFrame",false,"returnToIdle","DIRECT",
                    "generator","motion-harness","validation",clip.validation()));
            } else if(work.stepStatus().equals("PENDING")) {
                storage.ready();
                byte[] source=work.action()==AssetAction.BASE
                    ?SpriteNormalizer.reference(storage.photo(work.dogId(),work.photoBucket(),work.photoKey()))
                    :storage.asset(work.prefix()+"base.png");
                PhotoAppearance.Input references=null;
                if(work.action()==AssetAction.BASE) {
                    var prepared=store.preparation(work);
                    if(prepared!=null && prepared.path("status").asText().equals("STARTED")) {
                        store.fail(work,"FAILED","APPEARANCE_INTERRUPTED");return;
                    }
                    if(prepared==null || prepared.path("status").asText().equals("RETRY_READY")) {
                        if(!store.beginPreparation(work,appearance.model())) return;
                        var analysis=appearance.analyze(source);
                        references=PhotoAppearance.prepare(source,analysis);
                        if(!store.prepared(work,analysis,AssetRigService.sha256(source))) return;
                    } else {
                        if(!prepared.path("status").asText().equals("READY")
                            || !PhotoAppearance.VERSION.equals(prepared.path("version").asText())
                            || !AssetRigService.sha256(source).equals(prepared.path("photoSha256").asText()))
                            throw new AssetException(422,"PHOTO_APPEARANCE_SOURCE_CHANGED");
                        references=PhotoAppearance.prepare(source,prepared.path("analysis"));
                    }
                }
                if(!store.reserve(work)) return;
                reserved=true;
                var id=work.action()==AssetAction.BASE?provider.submitBase(references):provider.submit(work.action(),source);
                store.accepted(work,id);
            } else if(work.stepStatus().equals("WAITING")) {
                if(work.submittedAt()!=null && work.submittedAt().isBefore(Instant.now().minusSeconds(7200))) {
                    store.fail(work,"OUTCOME_UNKNOWN","PROVIDER_STALLED");return;
                }
                AssetProvider.Poll result;
                try { result=provider.poll(work.providerId()); }
                catch(AssetProvider.Failure e) { store.release(work,30);return; }
                if(result.status().equals("WAITING")) { store.release(work,15);return; }
                if(result.status().equals("FAILED")) { store.fail(work,"FAILED","PROVIDER_GENERATION_FAILED");return; }
                if(!store.authorized(work)) return;
                String key=work.prefix()+(work.action()==AssetAction.BASE?"base.png":work.action().name().toLowerCase(java.util.Locale.ROOT)+".png");
                Map<String,Object> metadata;
                byte[] png;
                if(work.action()==AssetAction.BASE) {
                    png=SpriteNormalizer.base(result.images());
                    metadata=Map.of("key",key,"frameCount",1,"width",64,"height",64);
                } else {
                    var clip=SpriteNormalizer.clip(result.images(),storage.asset(work.prefix()+"base.png"),work.action());
                    png=clip.sheet();
                    metadata=Map.of("key",key,"frameCount",clip.frameCount(),"width",64,"height",64,
                        "durationMs",work.action().durationMs,"loop",work.action().loop,"holdLastFrame",!work.action().loop,
                        "returnToIdle",work.action().loop?"DIRECT":"REVERSE_FRAMES","offsets",clip.offsets());
                }
                storage.put(key,png);
                if(work.action()==AssetAction.BASE) {
                    tools.jackson.databind.JsonNode proposed;
                    try { proposed=harness.propose(png); }
                    catch(AssetException e) { if(e.status!=422) throw e;proposed=null; }
                    store.baseReady(work,metadata,proposed,AssetRigService.sha256(png));
                } else store.success(work,metadata);
            }
        } catch(AssetProvider.Failure e) {
            store.fail(work,reserved&&e.uncertain?"OUTCOME_UNKNOWN":"FAILED",e.code);
        } catch(AssetException e) {
            if(MotionHarness.handles(work.action()) && e.status==422) store.rigNeedsReview(work);
            else if((work.stepStatus().equals("WAITING") || MotionHarness.handles(work.action())) && e.status>=500) store.release(work,30);
            else store.fail(work,reserved?"OUTCOME_UNKNOWN":"FAILED",e.code);
        } catch(RuntimeException e) {
            // Includes DB acknowledgement failure after a paid request. Never retry a POST without its id.
            if(work.stepStatus().equals("WAITING") || MotionHarness.handles(work.action())) store.release(work,30);
            else store.fail(work,reserved?"OUTCOME_UNKNOWN":"FAILED","ASSET_STEP_INTERRUPTED");
        }
    }
}
