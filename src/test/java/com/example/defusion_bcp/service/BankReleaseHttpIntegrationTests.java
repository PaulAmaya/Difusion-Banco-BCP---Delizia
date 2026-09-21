package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.BankPaymentDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "BCP_ALLOW_DOCUMENT_REVERSAL=true")
class BankReleaseHttpIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @MockitoBean BankPaymentStore store;
    MockMvc mvc;
    MockHttpSession portal;
    String csrfHeader;
    String csrfToken;
    jakarta.servlet.http.Cookie[] csrfCookies;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("qa-user", null, List.of());
        auth.setDetails(new SapSession("test", "cookie", Instant.now().plusSeconds(1800), "TEST_DB", "test"));
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        portal = new MockHttpSession();
        portal.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        var csrf = mvc.perform(get("/api/auth/csrf").session(portal)).andReturn().getResponse();
        csrfCookies = csrf.getCookies();
        var data = mapper.readTree(csrf.getContentAsString());
        csrfHeader = data.path("headerName").asString();
        csrfToken = data.path("token").asString();
    }

    @Test void acceptsExactFrontendJsonWithoutObsoleteBankReviewField() throws Exception {
        var response = mvc.perform(post("/api/bank/multiple-payments/4/release").session(portal)
            .cookie(csrfCookies).header(csrfHeader, csrfToken).contentType("application/json")
            .content("{\"reason\":\"Repetir prueba sandbox\",\"acknowledgeDuplicateRisk\":true}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        verify(store).release(eq(4L), eq("TEST_DB"), eq("qa-user"),
            argThat(body -> body.acknowledgeDuplicateRisk() && body.reason().equals("Repetir prueba sandbox")), anyString());
    }

    @Test void rejectsMissingRiskAcknowledgementAndDoesNotReleaseDocuments() throws Exception {
        var response = mvc.perform(post("/api/bank/multiple-payments/4/release").session(portal)
            .cookie(csrfCookies).header(csrfHeader, csrfToken).contentType("application/json")
            .content("{\"reason\":\"test\",\"acknowledgeDuplicateRisk\":false}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        verify(store, never()).release(any(), anyString(), anyString(), any(BankPaymentDtos.ReleaseRequest.class), anyString());
    }

    @Test void acceptsLegacyBankReviewFieldWithoutRequiringReconciliation() throws Exception {
        var response = mvc.perform(post("/api/bank/multiple-payments/4/release").session(portal)
            .cookie(csrfCookies).header(csrfHeader, csrfToken).contentType("application/json")
            .content("{\"reason\":\"test\",\"acknowledgeDuplicateRisk\":true,\"confirmBankReview\":false}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        verify(store).release(eq(4L), eq("TEST_DB"), eq("qa-user"), any(BankPaymentDtos.ReleaseRequest.class), anyString());
    }

    @Test void rejectsBlankReasonAndDoesNotReleaseDocuments() throws Exception {
        var response = mvc.perform(post("/api/bank/multiple-payments/4/release").session(portal)
            .cookie(csrfCookies).header(csrfHeader, csrfToken).contentType("application/json")
            .content("{\"reason\":\"  \",\"acknowledgeDuplicateRisk\":true}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        verify(store, never()).release(any(), anyString(), anyString(), any(BankPaymentDtos.ReleaseRequest.class), anyString());
    }
}
