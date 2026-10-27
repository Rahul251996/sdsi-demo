package com.digithon.assignment.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds a unique request ID to every incoming request for correlation.
 */
@Component
public class RequestIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String requestId = UUID.randomUUID().toString();
            MDC.put("requestId", requestId);
            if (request instanceof HttpServletRequest httpReq) {
                httpReq.setAttribute("requestId", requestId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}