package org.shelterconnect.api.web;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(OutputCaptureExtension.class)
class ApiRequestFilterTest {
    @Test void globalBudgetIsConsumedBeforeAuthenticationAndReturnsAServerGeneratedId() throws Exception {
        var filter=new ApiRequestFilter(new ApiTrafficPolicy(2,2,2,2,()->0L),new JsonMapper());
        for(int i=0;i<3;i++) {
            var request=new MockHttpServletRequest("GET","/v1/me");
            request.addHeader("Authorization","Bearer invalid-"+i);request.addHeader("X-Request-ID","client-secret");
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->{((jakarta.servlet.http.HttpServletResponse)s).setStatus(401);});
            assertThat(response.getStatus()).isEqualTo(i<2?401:429);
            assertThat(response.getHeader("X-Request-ID")).isNotEqualTo("client-secret");
            if(i==2) assertThat(response.getContentAsString()).contains("API_RATE_LIMITED").doesNotContain("client-secret","invalid-");
        }
        var health=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/actuator/health"),health,(r,s)->{});
        assertThat(health.getStatus()).isEqualTo(200);
    }
    @Test void unhandledFailureDoesNotSendPrivateMessageOrCauseToResponseOrLogs(CapturedOutput output) throws Exception {
        var filter=new ApiRequestFilter(new ApiTrafficPolicy(2,2,2,2,()->0L),new JsonMapper());
        var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/v1/me"),response,(r,s)->{
            throw new ServletException("private-query-input",new IllegalStateException("private-token-value"));
        });
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()+output.getAll()).doesNotContain("private-query-input","private-token-value");
        assertThat(output.getAll()).contains("type=ServletException","requestId=");
    }
}
