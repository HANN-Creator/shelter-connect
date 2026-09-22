package org.shelterconnect.api.asset;

import java.util.List;
import java.util.UUID;

public interface AssetProvider {
    UUID submit(AssetAction action, byte[] source);
    Poll poll(UUID providerJobId);
    record Poll(String status, List<byte[]> images) {}
    // A lost acknowledgement may already have incurred a charge. Never automatically submit it again.
    class Failure extends RuntimeException {
        final boolean uncertain;
        final String code;
        public Failure(String code, boolean uncertain) { super(code); this.code=code; this.uncertain=uncertain; }
    }
}
