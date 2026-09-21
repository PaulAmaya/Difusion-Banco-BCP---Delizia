package com.example.defusion_bcp.service;

import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.TriggerType;
import com.example.defusion_bcp.dto.StatementDtos;
import java.time.Instant;
import java.util.List;
import jakarta.servlet.Filter;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
class StatementHttpIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @MockitoBean StatementRequestService service;
    MockMvc mvc;
    MockHttpSession portal;
    String csrfHeader, csrfToken;
    jakarta.servlet.http.Cookie[] csrfCookies;
    String payload = "{\"accountNumber\":\"2015009988370\",\"period\":\"202506\"}";
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
        var json = mapper.readTree(csrf.getContentAsString());
        csrfHeader = json.path("headerName").asString(); csrfToken = json.path("token").asString();
    }
    @Test void acceptsAccountAndPeriodAndReturnsHttp200BusinessRejectionWithDecryptedMessage() throws Exception {
        var result = new StatementDtos.BankResponse(ProcessStatus.REJECTED, 200, false, "raw", null, "cipher", null, "Rechazo de prueba", null);
        when(service.query(any(), eq("qa-user"), eq("TEST_DB"), anyString())).thenReturn(new StatementDtos.Response(
            1L, "****5363", "202506", "qa-user", TriggerType.MANUAL, ProcessStatus.REJECTED, "correlation", "result", null, null, 200, result, "envelope"));
        var response = mvc.perform(post("/api/statements").session(portal).cookie(csrfCookies).header(csrfHeader, csrfToken)
            .contentType("application/json").content(payload)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        var body = mapper.readTree(response.getContentAsString());
        assertThat(body.path("bankResponse").path("decryptedMessage").asString()).isEqualTo("Rechazo de prueba");
        assertThat(body.path("httpStatus").asInt()).isEqualTo(200);
        verify(service).query(argThat(request -> request.period().equals("202506")
            && request.accountNumber().equals("2015009988370")), eq("qa-user"), eq("TEST_DB"), anyString());
    }
    @Test void rejectsInvalidCalendarMonthBeforeAnyBankCall() throws Exception {
        var response = mvc.perform(post("/api/statements").session(portal).cookie(csrfCookies).header(csrfHeader, csrfToken)
            .contentType("application/json").content(payload.replace("202506", "202513"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }
    @Test void detailAndHistoryUseAuthenticatedSapCompany() throws Exception {
        mvc.perform(get("/api/statements").session(portal));
        mvc.perform(get("/api/statements/7").session(portal));
        verify(service).listRecent("TEST_DB");
        verify(service).detail(7L, "TEST_DB");
    }
    @Test void configurationContainsNoGenericCredentialsOrTestAccount() throws Exception {
        when(service.configuration()).thenReturn(new StatementDtos.Configuration("2015009988370", "202609",
            List.of(new StatementDtos.Account("BCP LP", "2015009988370"), new StatementDtos.Account("BCP SC", "20150838488388")),
            new BankPaymentClient.Availability(false, false, "PRODUCTION", "No habilitado")));
        var response = mvc.perform(get("/api/statements/config").session(portal)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain("password", "documentNumber", "payload", "20150735205363");
    }
}
