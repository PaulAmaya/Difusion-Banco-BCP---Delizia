package com.example.defusion_bcp.service;

import com.example.defusion_bcp.controller.SapVendorPaymentController;
import com.example.defusion_bcp.dto.DiffusionDtos;
import com.example.defusion_bcp.dto.SapVendorPaymentDtos;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SapVendorPaymentFilterTests {
    @Test
    void defaultsBothDatesToTodayInBoliviaAndUsesBackendFiltersAndExclusions() {
        var client = mock(SapClient.class);
        var audit = mock(AuditLogService.class);
        var diffusion = mock(DiffusionPreviewService.class);
        var store = mock(BankPaymentStore.class);
        var region = new DiffusionDtos.Region("LP", "La Paz", 201);
        when(diffusion.catalogs()).thenReturn(new DiffusionDtos.Catalogs(
            List.of(new DiffusionDtos.CodeName("1016", "Banco Economico")), List.of(), List.of(), List.of(region)));
        var session = new SapSession("test", "cookie", Instant.now().plusSeconds(60), "TEST_DB", "test");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        authentication.setDetails(session);
        var today = LocalDate.now(java.time.ZoneId.of("America/La_Paz"));
        var expected = new SapVendorPaymentDtos.PaymentListResponse(List.of(), 0, 20, 0, 0, false, false,
            today, today, "LP", "1016", "11010501");
        when(store.blockedIds("TEST_DB")).thenReturn(Set.of(99L));
        when(client.vendorPayments(session, today, today, 0, 20, region, "1016", "11010501", Set.of(99L))).thenReturn(expected);
        var controller = new SapVendorPaymentController(client, audit, diffusion, store);
        assertThat(controller.list(null, null, 0, 20, " lp ", " 1016 ", "11010501", authentication,
            new MockHttpServletRequest())).isSameAs(expected);
        verify(client).vendorPayments(session, today, today, 0, 20, region, "1016", "11010501", Set.of(99L));
    }

    @Test
    void whenOnlyOneDateIsProvidedUsesItForBothEndsOfTheRange() {
        var client = mock(SapClient.class);
        var diffusion = mock(DiffusionPreviewService.class);
        var session = new SapSession("test", "cookie", Instant.now().plusSeconds(60), "TEST_DB", "test");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        authentication.setDetails(session);
        var date = LocalDate.of(2026, 9, 1);
        var expected = new SapVendorPaymentDtos.PaymentListResponse(List.of(), 0, 20, 0, 0, false, false, date, date, null);
        when(client.vendorPayments(session, date, date, 0, 20, null, "", "11010501", Set.of())).thenReturn(expected);
        var controller = new SapVendorPaymentController(client, mock(AuditLogService.class), diffusion, mock(BankPaymentStore.class));
        assertThat(controller.list(date, null, 0, 20, "", "", "11010501", authentication, new MockHttpServletRequest())).isSameAs(expected);
        assertThat(controller.list(null, date, 0, 20, "", "", "11010501", authentication, new MockHttpServletRequest())).isSameAs(expected);
    }
    @Test
    void rejectsUnknownDepartmentsBeforeMakingAnySapRequest() {
        SapClient client = mock(SapClient.class);
        var audit = mock(AuditLogService.class);
        var diffusion = mock(DiffusionPreviewService.class);
        when(diffusion.catalogs()).thenReturn(new DiffusionDtos.Catalogs(List.of(), List.of(), List.of(),
            List.of(new DiffusionDtos.Region("LP", "La Paz", 201))));
        var controller = new SapVendorPaymentController(client, audit, diffusion, mock(BankPaymentStore.class));
        assertThatThrownBy(() -> controller.list(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, "SN", "", "11010501", null, null)).isInstanceOf(SapServiceException.class).hasMessageContaining("nueve departamentos");
        verifyNoInteractions(client, audit);
    }

    @Test
    void acceptsNormalizedDepartmentCodesAndPassesTheCatalogRegionToSap() {
        SapClient client = mock(SapClient.class);
        var audit = mock(AuditLogService.class);
        var diffusion = mock(DiffusionPreviewService.class);
        var region = new DiffusionDtos.Region("LP", "La Paz", 201);
        when(diffusion.catalogs()).thenReturn(new DiffusionDtos.Catalogs(List.of(), List.of(), List.of(), List.of(region)));
        var session = new SapSession("test", "test-cookie", Instant.now().plusSeconds(60), "TEST_DB", "10.0");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        authentication.setDetails(session);
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var expected = new SapVendorPaymentDtos.PaymentListResponse(List.of(), 0, 20, 0, 0, false, false, from, to, "LP");
        var store = mock(BankPaymentStore.class);
        when(store.blockedIds("TEST_DB")).thenReturn(Set.of(9L));
        when(client.vendorPayments(session, from, to, 0, 20, region, "", "11010501", Set.of(9L))).thenReturn(expected);
        var controller = new SapVendorPaymentController(client, audit, diffusion, store);
        assertThat(controller.list(from, to, 0, 20, " lp ", "", "11010501", authentication, new MockHttpServletRequest())).isSameAs(expected);
        verify(client).vendorPayments(session, from, to, 0, 20, region, "", "11010501", Set.of(9L));
    }

    @Test
    void rejectsUnknownBanksBeforeReadingSapOrCreatingAnAudit() {
        var client = mock(SapClient.class);
        var audit = mock(AuditLogService.class);
        var diffusion = mock(DiffusionPreviewService.class);
        when(diffusion.catalogs()).thenReturn(new DiffusionDtos.Catalogs(List.of(), List.of(), List.of(), List.of()));
        var controller = new SapVendorPaymentController(client, audit, diffusion, mock(BankPaymentStore.class));
        assertThatThrownBy(() -> controller.list(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            0, 20, "", "UNKNOWN", "11010501", null, null)).isInstanceOf(SapServiceException.class).hasMessageContaining("banco del catalogo");
        verifyNoInteractions(client, audit);
    }

    @Test
    void passesNormalizedBankCodesWithTheDepartmentAndAuditsTheBankName() {
        var client = mock(SapClient.class);
        var audit = mock(AuditLogService.class);
        var diffusion = mock(DiffusionPreviewService.class);
        var region = new DiffusionDtos.Region("LP", "La Paz", 201);
        when(diffusion.catalogs()).thenReturn(new DiffusionDtos.Catalogs(
            List.of(new DiffusionDtos.CodeName("1016", "Banco Economico")), List.of(), List.of(), List.of(region)));
        var session = new SapSession("test", "test-cookie", Instant.now().plusSeconds(60), "TEST_DB", "10.0");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of());
        authentication.setDetails(session);
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 7, 31);
        var expected = new SapVendorPaymentDtos.PaymentListResponse(List.of(), 0, 20, 0, 0, false, false, from, to, "LP", "1016");
        when(client.vendorPayments(session, from, to, 0, 20, region, "1016", "11010501", Set.of())).thenReturn(expected);
        var controller = new SapVendorPaymentController(client, audit, diffusion, mock(BankPaymentStore.class));
        assertThat(controller.list(from, to, 0, 20, "lp", " 1016 ", "11010501", authentication, new MockHttpServletRequest())).isSameAs(expected);
        verify(client).vendorPayments(session, from, to, 0, 20, region, "1016", "11010501", Set.of());
        verify(audit).record(any(), any(), any(), any(), eq("test-user"), any(), any(),
            contains("del banco Banco Economico"), any(), any());
    }
}
