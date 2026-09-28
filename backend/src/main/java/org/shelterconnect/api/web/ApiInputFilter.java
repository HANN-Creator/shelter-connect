package org.shelterconnect.api.web;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Runs after authorization, before multipart/JSON conversion or controller side effects. */
public final class ApiInputFilter extends OncePerRequestFilter {
    public static final int BODY_BYTES=64*1024, PHOTO_BYTES=5*1024*1024, MULTIPART_BYTES=6*1024*1024;
    private final ApiTrafficPolicy traffic;
    private final JsonMapper json;
    public ApiInputFilter(ApiTrafficPolicy traffic,JsonMapper json) { this.traffic=traffic; this.json=json; }
    static boolean api(HttpServletRequest request) { return request.getRequestURI().startsWith(request.getContextPath()+"/v1/"); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !api(request); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        String subject=authentication instanceof JwtAuthenticationToken token?token.getToken().getSubject():null;
        int wait=traffic.admitUser(subject,!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()));
        if(wait>0) { ApiRequestFilter.tooMany(json,request,response,wait); return; }
        String encoding=request.getHeader("Content-Encoding");
        if(encoding!=null && !encoding.equalsIgnoreCase("identity")) {
            ApiRequestFilter.error(json,request,response,415,"UNSUPPORTED_CONTENT_ENCODING","압축하지 않은 요청 본문을 보내 주세요."); return;
        }
        String type=request.getContentType();
        if(type!=null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            if(request.getContentLengthLong()>MULTIPART_BYTES) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
            try {
                var parts=request.getParts();
                if(parts.size()>4) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
                long total=0;
                for(var part:parts) {
                    total+=part.getSize();
                    boolean file=part.getName().equals("file");
                    if(part.getSize()>(file?PHOTO_BYTES:BODY_BYTES)) { tooLarge(request,response,file?"PHOTO_TOO_LARGE":"REQUEST_TOO_LARGE"); return; }
                }
                if(total>MULTIPART_BYTES) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
            } catch(IllegalStateException ex) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
            catch(ServletException ex) {
                ApiRequestFilter.error(json,request,response,400,"INVALID_REQUEST","multipart 파일과 입력 항목을 확인해 주세요."); return;
            }
            chain.doFilter(request,response); return;
        }
        if(request.getContentLengthLong()>BODY_BYTES) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
        // Also bounds chunked/missing/incorrect Content-Length; never allocates the full body.
        byte[] body=request.getInputStream().readNBytes(BODY_BYTES+1);
        if(body.length>BODY_BYTES) { tooLarge(request,response,"REQUEST_TOO_LARGE"); return; }
        chain.doFilter(new BufferedRequest(request,body),response);
    }
    private void tooLarge(HttpServletRequest request,HttpServletResponse response,String code) throws IOException {
        ApiRequestFilter.error(json,request,response,413,code,"본문은 64KiB, 사진은 5MiB, multipart 전체는 6MiB 이하여야 해요.");
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request,byte[] body) { super(request);this.body=body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b,int offset,int length) { return input.read(b,offset,length); }
                @Override public boolean isFinished() { return input.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API input"); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
    }
}
