package com.example.defusion_bcp.service;

import com.example.defusion_bcp.security.SapAuthenticationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SapAuthenticationProviderTests {
    @Test
    void authenticatedUserContainsSapSessionWithoutPassword() {
        SapClient sapClient = mock(SapClient.class);
        SapSession sapSession = new SapSession(
            "session-id", "B1SESSION=session-id", Instant.now().plusSeconds(1500), "TEST_DB", "10.0"
        );
        when(sapClient.login("sap-user", "secret")).thenReturn(sapSession);
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
}
