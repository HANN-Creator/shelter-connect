package org.shelterconnect.api.web;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class ApiTrafficPolicyTest {
    @Test void rollingWindowsRetryAfterAndUserIsolation() {
        var clock=new AtomicLong(0); var policy=new ApiTrafficPolicy(3,2,3,1,clock::get);
        assertThat(policy.admitUser("a",true)).isZero();
        assertThat(policy.admitUser("a",true)).isEqualTo(60);
        assertThat(policy.admitUser("a",false)).isZero();
        assertThat(policy.admitUser("b",true)).isZero();
        clock.set(TimeUnit.SECONDS.toNanos(59));
        assertThat(policy.admitUser("a",true)).isEqualTo(1);
        clock.set(TimeUnit.SECONDS.toNanos(60));
        assertThat(policy.admitUser("a",true)).isZero();
        assertThat(policy.admitUser(null,false)).isZero();
        assertThat(policy.admitUser(null,false)).isZero();
        assertThat(policy.admitUser(null,false)).isEqualTo(60);
    }
    @Test void globalAndUserCountersAreAtomicUnderConcurrency() throws Exception {
        var policy=new ApiTrafficPolicy(25,20,10,5,()->0L);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var passed=new AtomicInteger(); var userPassed=new AtomicInteger();
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<200;i++) tasks.add(workers.submit(()->{
                if(policy.admitGlobal()==0) passed.incrementAndGet();
                if(policy.admitUser("a",true)==0) userPassed.incrementAndGet();
            }));
            for(var task:tasks) task.get();
            assertThat(passed).hasValue(25); assertThat(userPassed).hasValue(5);
        }
    }
    @Test void fullIdentityTableCannotEvictActiveLimitsAndExpiredEntriesAreReclaimed() {
        var clock=new AtomicLong(0); var policy=new ApiTrafficPolicy(1,1,1,1,clock::get);
        for(int i=0;i<4096;i++) assertThat(policy.admitUser("user-"+i,false)).isZero();
        assertThat(policy.admitUser("new",false)).isEqualTo(60);
        assertThat(policy.admitUser("user-0",false)).isEqualTo(60);
        clock.set(TimeUnit.SECONDS.toNanos(60));
        assertThat(policy.admitUser("new",false)).isZero();
    }
    @Test void invalidConfigurationCannotDisableLimits() {
        for(String value:new String[]{"0","-1","10001"})
            assertThatThrownBy(()->new ApiTrafficPolicy(new MockEnvironment().withProperty("app.traffic.user-minute-limit",value)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
