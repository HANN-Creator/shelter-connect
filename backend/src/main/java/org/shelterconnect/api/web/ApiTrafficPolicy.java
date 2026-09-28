package org.shelterconnect.api.web;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;

/** Rolling windows for one API process. Never trust an IP/header supplied by a client. */
public final class ApiTrafficPolicy {
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_USERS = 4096;
    private final int globalLimit, anonymousLimit, userLimit, writeLimit;
    private final LongSupplier clock;
    private final ArrayDeque<Long> global = new ArrayDeque<>(), anonymous = new ArrayDeque<>();
    private final Map<String, UserWindow> users = new HashMap<>();
    private long lastSweep;

    public ApiTrafficPolicy(Environment env) {
        this(limit(env,"global",1200),limit(env,"anonymous",300),limit(env,"user",180),limit(env,"write",60),System::nanoTime);
    }
    ApiTrafficPolicy(int globalLimit,int anonymousLimit,int userLimit,int writeLimit,LongSupplier clock) {
        this.globalLimit=globalLimit; this.anonymousLimit=anonymousLimit;
        this.userLimit=userLimit; this.writeLimit=writeLimit; this.clock=clock;
        lastSweep=clock.getAsLong();
    }
    private static int limit(Environment env,String name,int fallback) {
        int value=env.getProperty("app.traffic."+name+"-minute-limit",Integer.class,fallback);
        if(value<1 || value>10000) throw new IllegalArgumentException("API traffic limits must be between 1 and 10000");
        return value;
    }
    public synchronized int admitGlobal() { return admit(global,globalLimit,clock.getAsLong()); }
    public synchronized int admitUser(String subject,boolean write) {
        long now=clock.getAsLong();
        if(subject==null) return admit(anonymous,anonymousLimit,now);
        if(now-lastSweep>=WINDOW || users.size()>=MAX_USERS) {
            users.values().removeIf(u -> now-u.lastSeen>=WINDOW);
            lastSweep=now;
        }
        var window=users.get(subject);
        if(window==null) {
            // Do not evict active counters: that would let new identities bypass the limit.
            if(users.size()>=MAX_USERS) return 60;
            window=new UserWindow(); users.put(subject,window);
        }
        window.lastSeen=now;
        int wait=Math.max(waitSeconds(window.requests,userLimit,now),write?waitSeconds(window.writes,writeLimit,now):0);
        if(wait==0) { window.requests.addLast(now); if(write) window.writes.addLast(now); }
        return wait;
    }
    private static int admit(ArrayDeque<Long> queue,int limit,long now) {
        int wait=waitSeconds(queue,limit,now);
        if(wait==0) queue.addLast(now);
        return wait;
    }
    private static int waitSeconds(ArrayDeque<Long> queue,int limit,long now) {
        while(!queue.isEmpty() && now-queue.peekFirst()>=WINDOW) queue.removeFirst();
        if(queue.size()<limit) return 0;
        return (int)Math.max(1,(WINDOW-(now-queue.peekFirst())+999_999_999L)/1_000_000_000L);
    }
    private static final class UserWindow {
        final ArrayDeque<Long> requests=new ArrayDeque<>(), writes=new ArrayDeque<>();
        long lastSeen;
    }
}
