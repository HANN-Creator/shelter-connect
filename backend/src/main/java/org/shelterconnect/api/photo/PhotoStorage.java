package org.shelterconnect.api.photo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.shelterconnect.api.photo.PhotoTypes.*;

public interface PhotoStorage {
	Map<UUID, Signed> sign(List<Stored> photos);
}
