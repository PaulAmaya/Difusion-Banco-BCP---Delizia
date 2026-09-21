package com.example.defusion_bcp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
class SapSessionSecurityIntegrationTests {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    private MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build(); }
    private MockHttpSession portalSession(SapSession sap) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("qa-user", null, List.of());
        auth.setDetails(sap);
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }
    @Test void expiredBankPostIsRejectedBeforeCsrfAndControllerExecution() throws Exception {
        var sap = new SapSession("test", "cookie", Instant.now().minusSeconds(1), "TEST_DB", "test");
        var portal = portalSession(sap);
        var response = mvc.perform(post("/api/bank/multiple-payments").session(portal)
            .contentType("application/json").content("{}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SAP_SESSION_EXPIRED");
        assertThat(portal.isInvalid()).isTrue();
    }
    @Test void meExposesAuthoritativeSapDeadlineWithoutRenewingIt() throws Exception {
        var sap = new SapSession("test", "cookie", Instant.now().plusSeconds(1800), "TEST_DB", "test");
        var deadline = sap.expiresAt();
        var response = mvc.perform(get("/api/auth/me").session(portalSession(sap))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data = new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(Instant.parse(data.path("sapExpiresAt").asString())).isEqualTo(deadline);
        assertThat(sap.expiresAt()).isEqualTo(deadline);
    }
    @Test void missingPortalSessionReturns401ForFrontendExpiryHandling() throws Exception {
        var response = mvc.perform(get("/api/auth/me")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_REQUIRED");
    }
}
