package com.example.defusion_bcp.service;

import com.example.defusion_bcp.security.SapIdleSessionFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SapIdleSessionTests {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void capsIdleTimeAt30MinutesAndSlidesOnlyOnSuccessfulSapQueries() {
        var session = new SapSession("test", "cookie", Instant.now().plusSeconds(3600), "DB", "test");
        Instant original = session.expiresAt();
        assertThat(Duration.between(Instant.now(), original).toSeconds()).isBetween(1798L, 1800L);
        session.successfulQuery(original.minusSeconds(60));
        assertThat(session.expiresAt()).isEqualTo(original.plusSeconds(1740));
        Instant latest = session.expiresAt();
        assertThat(session.expired(latest)).isTrue();
        session.successfulQuery(latest.plusSeconds(1));
        assertThat(session.expiresAt()).isEqualTo(latest);
        session.expire();
        assertThat(session.expired(Instant.now())).isTrue();
    }
    @Test void respectsShorterSapTimeout() {
        var session = new SapSession("test", "cookie", Instant.now().plusSeconds(300), "DB", "test");
        Instant previous = session.expiresAt();
        session.successfulQuery(previous.minusSeconds(1));
        assertThat(Duration.between(previous, session.expiresAt()).toSeconds()).isBetween(297L, 299L);
    }
    @Test void expiredSessionBlocksNonSapRequestsAndInvalidatesPortalSession() throws Exception {
        var session = new SapSession("test", "cookie", Instant.now().minusSeconds(1), "DB", "test");
        var auth = UsernamePasswordAuthenticationToken.authenticated("actor", null, List.of());
        auth.setDetails(session);
        SecurityContextHolder.getContext().setAuthentication(auth);
        var request = new MockHttpServletRequest("POST", "/api/bank/multiple-payments");
        var httpSession = request.getSession();
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new SapIdleSessionFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SAP_SESSION_EXPIRED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(() -> httpSession.getAttribute("anything")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(chain);
    }
    @Test void ordinaryPortalRequestsDoNotRefreshSapInactivity() throws Exception {
        var session = new SapSession("test", "cookie", Instant.now().plusSeconds(1800), "DB", "test");
        Instant expiry = session.expiresAt();
        var auth = UsernamePasswordAuthenticationToken.authenticated("actor", null, List.of());
        auth.setDetails(session);
        SecurityContextHolder.getContext().setAuthentication(auth);
        var request = new MockHttpServletRequest("GET", "/api/audit-logs");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new SapIdleSessionFilter().doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(session.expiresAt()).isEqualTo(expiry);
    }
}
