package org.shelterconnect.api.web;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class ApiInputFilterTest {
    private final ApiInputFilter filter=new ApiInputFilter(new ApiTrafficPolicy(10,10,10,10,()->0L),new JsonMapper());
    @Test void exactBodyBoundaryIsReplayedButMissingOrLyingLengthCannotBypassTheLimit() throws Exception {
        for(int declared:new int[]{-1,1,ApiInputFilter.BODY_BYTES}) {
            var request=new MockHttpServletRequest("POST","/v1/me") {
                @Override public long getContentLengthLong() { return declared; }
            };
            request.setContent(" ".repeat(ApiInputFilter.BODY_BYTES).getBytes(StandardCharsets.UTF_8));
            var ran=new AtomicBoolean();
            filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{
                assertThat(r.getReader().readLine()).hasSize(ApiInputFilter.BODY_BYTES); ran.set(true);
            });
            assertThat(ran).isTrue();
            request.setContent(new byte[ApiInputFilter.BODY_BYTES+1]);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->{ throw new AssertionError("Oversized body reached handler"); });
            assertThat(response.getStatus()).isEqualTo(413);
        }
    }
    @Test void compressedInputIsRejectedBeforeParsing() throws Exception {
        var request=new MockHttpServletRequest("POST","/v1/me");request.addHeader("Content-Encoding","gzip");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(415);
    }
    @Test void oversizedMetadataIsRejectedEvenWhenNamedAsAFile() throws Exception {
        var request=new MockHttpServletRequest("POST","/v1/shelter-admin/dogs/a/photos");
        request.setContentType("multipart/form-data; boundary=test");
        request.addPart(new MockPart("metadata","metadata.json",new byte[ApiInputFilter.BODY_BYTES+1]));
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
