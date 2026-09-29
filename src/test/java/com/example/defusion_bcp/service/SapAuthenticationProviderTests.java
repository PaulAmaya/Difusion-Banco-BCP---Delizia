package com.example.defusion_bcp.service;

import com.example.defusion_bcp.security.SapAuthenticationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SapAuthenticationProviderTests {
    @Test
    void treasuryUserContainsSapSessionWithoutPassword() {
        SapClient sapClient = mock(SapClient.class);
        SapSession sapSession = new SapSession(
            "session-id", "B1SESSION=session-id", Instant.now().plusSeconds(1500), "TEST_DB", "10.0"
        );
        when(sapClient.login("sap-user", "secret")).thenReturn(sapSession);
        when(sapClient.userAccess(sapSession, "sap-user"))
            .thenReturn(Optional.of(new SapClient.SapUserAccess("sap-user", "P", "TES_LP")));
        SapAuthenticationProvider provider = new SapAuthenticationProvider(sapClient);

        Authentication result = provider.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("sap-user", "secret")
        );

        verify(sapClient).login("sap-user", "secret");
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getDetails()).isEqualTo(sapSession);
        assertThat(result.getAuthorities()).extracting("authority")
            .containsExactlyInAnyOrder("ROLE_TREASURY", "FACTOR_SAP");
    }

    @Test
    void santaCruzTreasuryUserCanLogIn() {
        SapClient sapClient = mock(SapClient.class);
        SapSession session = session();
        when(sapClient.login("treasury", "secret")).thenReturn(session);
        when(sapClient.userAccess(session, "treasury"))
            .thenReturn(Optional.of(new SapClient.SapUserAccess("treasury", "P", "TES_SC")));

        Authentication result = new SapAuthenticationProvider(sapClient).authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("treasury", "secret"));

        assertThat(result.getAuthorities()).extracting("authority").contains("ROLE_TREASURY").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void onlyNamedAdminsBypassSapUserFields() {
        for (String username : new String[]{"SIS37", "SIS21"}) {
            SapClient sapClient = mock(SapClient.class);
            SapSession session = session();
            when(sapClient.login(username, "secret")).thenReturn(session);

            Authentication result = new SapAuthenticationProvider(sapClient).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, "secret"));

            assertThat(result.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TREASURY", "FACTOR_SAP");
            verify(sapClient, never()).userAccess(session, username);
        }
    }

    @Test
    void invalidSapUserFieldsAndMissingUserAreRejectedAndLoggedOut() {
        for (SapClient.SapUserAccess user : new SapClient.SapUserAccess[]{
            new SapClient.SapUserAccess("employee", "X", "TES_LP"),
            new SapClient.SapUserAccess("employee", "P", "OTHER"),
            new SapClient.SapUserAccess("employee", null, null),
            null
        }) {
            SapClient sapClient = mock(SapClient.class);
            SapSession session = session();
            when(sapClient.login("employee", "secret")).thenReturn(session);
            when(sapClient.userAccess(session, "employee")).thenReturn(Optional.ofNullable(user));

            assertThatThrownBy(() -> new SapAuthenticationProvider(sapClient).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("employee", "secret")))
                .isInstanceOf(DisabledException.class);
            verify(sapClient).logoutQuietly(session);
        }
    }

    private SapSession session() {
        return new SapSession("session-id", "B1SESSION=session-id", Instant.now().plusSeconds(1500), "TEST_DB", "10.0");
    }
}
