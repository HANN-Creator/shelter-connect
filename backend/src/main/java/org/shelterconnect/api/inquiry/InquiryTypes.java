package org.shelterconnect.api.inquiry;

import java.time.Instant;
import java.util.*;

public final class InquiryTypes {
    private InquiryTypes() {}
    public record SharedLocation(String label,double latitude,double longitude) {}
    public record Message(UUID id,UUID roomId,long sequence,UUID senderId,boolean mine,String kind,String text,
                          UUID mediaId,SharedLocation location,Instant createdAt) {}
    public record Counterpart(UUID id,String nickname,String avatarKey,boolean available) {}
    public record PostContext(UUID id,boolean available,String title,String category,String status,UUID thumbnailMediaId) {}
    public record Room(UUID id,UUID postId,Counterpart counterpart,PostContext post,Message lastMessage,long unreadCount,
                       long lastSequence,long readSequence,long counterpartReadSequence,boolean canSend,Instant updatedAt) {}
    public record Rooms(List<Room> data,String nextCursor,long unreadRoomCount) {}
    public record Messages(List<Message> data,Long olderBeforeSequence,Long nextAfterSequence,boolean hasMore) {}
    public record ReadState(UUID roomId,long readSequence,long unreadCount) {}
}
