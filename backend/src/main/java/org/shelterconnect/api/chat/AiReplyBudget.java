package org.shelterconnect.api.chat;

import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AiReplyBudget {
    private final JdbcClient jdbc;
    private final AiBudgetProperties limits;
    public AiReplyBudget(JdbcClient jdbc, AiBudgetProperties limits) { this.jdbc=jdbc; this.limits=limits; }

    public void reserve(UUID user, UUID request, UUID token, int leaseSeconds) {
        // Only the short reservation transaction holds this lock, never the external call.
        // A database lock is shared by all server instances; a process-local counter is not.
        jdbc.sql("SELECT pg_advisory_xact_lock(73922901)").query((rs,n)->0).single();
        var now=jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().withOffsetSameInstant(ZoneOffset.UTC);
        var day=now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        var usage=jdbc.sql("""
            SELECT count(*) FILTER (WHERE user_id=:user AND reserved_at > :minute) AS minute_used,
                   count(*) FILTER (WHERE user_id=:user AND reserved_at >= :day) AS user_day,
                   count(*) FILTER (WHERE reserved_at >= :day) AS global_day,
                   count(*) FILTER (WHERE released_at IS NULL AND expires_at > :now) AS active
            FROM shelter.ai_reply_usage
            WHERE reserved_at >= LEAST(:day, :minute) OR (released_at IS NULL AND expires_at > :now)
            """).param("user",user).param("minute",now.minusMinutes(1)).param("day",day).param("now",now)
            .query((rs,n)->new long[]{rs.getLong("minute_used"),rs.getLong("user_day"),rs.getLong("global_day"),rs.getLong("active")}).single();
        int untilTomorrow=(int)Math.max(1,Duration.between(now,day.plusDays(1)).toSeconds()+1);
        if(usage[0]>=limits.userMinute()) throw ChatException.limited("AI_USER_MINUTE_LIMIT","질문이 잠시 몰렸어요. 1분 뒤 다시 이야기해 주세요.",60);
        if(usage[1]>=limits.userDay()) throw ChatException.limited("AI_USER_DAILY_LIMIT","오늘의 대화 생성 한도에 도달했어요. 이전 대화는 계속 볼 수 있어요.",untilTomorrow);
        if(usage[2]>=limits.globalDay()) throw ChatException.limited("AI_DAILY_LIMIT","오늘 준비된 대화 생성량을 모두 사용했어요. 잠시 쉬었다 다시 만나요.",untilTomorrow);
        if(usage[3]>=limits.concurrent()) throw ChatException.limited("AI_BUSY","다른 답변을 준비하고 있어요. 잠시 후 다시 보내 주세요.",5);
        jdbc.sql("INSERT INTO shelter.ai_reply_usage(token,user_id,request_message_id,reserved_at,expires_at) VALUES (:token,:user,:message,:now,:expires)")
            .param("token",token).param("user",user).param("message",request).param("now",now).param("expires",now.plusSeconds(leaseSeconds)).update();
    }

    public void release(UUID token) {
        // Preserve the charged attempt even on failure; only concurrency capacity is released.
        jdbc.sql("UPDATE shelter.ai_reply_usage SET released_at=clock_timestamp() WHERE token=:token AND released_at IS NULL")
            .param("token",token).update();
    }
}
