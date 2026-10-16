package ru.lct.heatnet.api;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;

/** Bound session metadata before Jackson materializes it. Binary parts are streamed/bounded by UploadService. */
@Component
public class UploadRequestFilter extends OncePerRequestFilter {
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Use the same decoded, context-relative path semantics as Spring MVC (including matrix parameters).
        String path = PATHS.getPathWithinApplication(request);
        boolean upload = path.equals("/api/v1/uploads") || path.startsWith("/api/v1/uploads/");
        if (upload) response.setHeader("Cache-Control", "no-store");
        if (!upload || !"POST".equals(request.getMethod())) {
            chain.doFilter(request,response); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(8193);
        if (bytes.length > 8192) {
            response.setStatus(413); response.setContentType("application/json");
            response.getWriter().write("{\"status\":413,\"message\":\"Upload session metadata exceeds 8 KiB\"}"); return;
        }
        ByteArrayInputStream body = new ByteArrayInputStream(bytes);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return body.read(); }
                    @Override public int read(byte[] b,int off,int len) { return body.read(b,off,len); }
                    @Override public boolean isFinished() { return body.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous bounded metadata"); }
                };
            }
        },response);
    }
}
