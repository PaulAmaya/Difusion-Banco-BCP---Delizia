package com.example.defusion_bcp.security;

import com.example.defusion_bcp.service.SapSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;

public final class SapIdleSessionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof SapSession session
            && session.expired(Instant.now())) {
            var httpSession = request.getSession(false);
            if (httpSession != null) httpSession.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"SAP_SESSION_EXPIRED\",\"message\":\"Su sesion SAP ha expirado por inactividad. Inicie sesion nuevamente.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
