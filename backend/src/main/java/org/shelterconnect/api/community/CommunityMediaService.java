package org.shelterconnect.api.community;

import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.shelterconnect.api.web.FeatureException;
import static org.shelterconnect.api.community.CommunityTypes.*;

@Service
public class CommunityMediaService {
    private final CommunityMediaStore store;private final CommunityStorage storage;
    public CommunityMediaService(CommunityMediaStore store,CommunityStorage storage){this.store=store;this.storage=storage;}
    public Media upload(UUID subject,UUID request,byte[] input) {
        store.authorize(subject);storage.checkEnabled();
        byte[] png=CommunityImage.normalize(input);String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));}catch(Exception ex){throw new IllegalStateException(ex);}
        var reserved=store.reserve(subject,request,hash,png.length);
        if(reserved.state().equals("READY"))return reserved.response();
        storage.put(reserved.key(),png);return store.complete(subject,reserved.id()).response();
    }
    public MediaLink link(UUID subject,UUID id) {
        var item=store.accessible(subject,id);Instant before=Instant.now();String url=storage.sign(item.key());
        store.accessible(subject,id); // Recheck after external I/O, including moderation/account changes.
        return new MediaLink(id,url,before.plusSeconds(60));
    }
}
