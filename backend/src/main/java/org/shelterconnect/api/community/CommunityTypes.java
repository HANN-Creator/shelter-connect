package org.shelterconnect.api.community;

import java.time.Instant;
import java.util.*;

public final class CommunityTypes {
    private CommunityTypes() {}
    public record Location(String label,Double latitude,Double longitude,Instant occurredAt) {}
    public record Content(String title,String text,String regionLabel,Location location,List<String> features,List<UUID> mediaIds) {}
    public record Post(UUID id,UUID authorId,String authorName,boolean mine,String category,String publication,
                       String status,long version,Content content,boolean hidden,Instant createdAt,Instant publishedAt,
                       Instant updatedAt,long commentCount,long sightingCount) {}
    public record CommentContent(String text,Location location,List<UUID> mediaIds) {}
    public record Comment(UUID id,UUID postId,UUID authorId,String authorName,boolean mine,String kind,UUID parentId,
                          CommentContent content,boolean deleted,Instant createdAt) {}
    public record Region(String regionLabel) {}
    public record Mutation(UUID id,long version) {}
    public record ReportReceipt(UUID id,String status) {}
    public record Report(UUID id,UUID postId,UUID reporterId,String reason,String details,String status,long version,
                         String reviewNote,Instant createdAt,Instant reviewedAt,Content postContent) {}
    public record Media(UUID id,String state,int byteSize) {}
    public record MediaLink(UUID id,String url,Instant expiresAt) {}
}
