package com.example.defusion_bcp.service;

import org.junit.jupiter.api.Test;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.SSLEngine;
import java.security.Principal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankTlsKeyManagerTests {
    @Test void tracksSelectionWithoutChangingChosenKeyOrTrustBehavior() {
        var delegate = mock(X509ExtendedKeyManager.class);
        var trace = new BankTlsKeyManager.Trace();
        var manager = new BankTlsKeyManager(delegate, "qa-request", trace);
        String[] types = {"RSA"};
        Principal[] issuers = {};
        var engine = mock(SSLEngine.class);
        when(delegate.chooseEngineClientAlias(types, issuers, engine)).thenReturn("private-alias");
        assertThat(manager.chooseEngineClientAlias(types, issuers, engine)).isEqualTo("private-alias");
        assertThat(trace.requested.get()).isEqualTo(1);
        assertThat(trace.selected.get()).isEqualTo(1);
        assertThat(manager.getPrivateKey("private-alias")).isNull();
        verify(delegate).getPrivateKey("private-alias");
    }

    @Test void recordsWhenServerRequestsACertificateButNoAliasMatches() {
        var trace = new BankTlsKeyManager.Trace();
        var manager = new BankTlsKeyManager(mock(X509ExtendedKeyManager.class), "qa-request", trace);
        assertThat(manager.chooseEngineClientAlias(new String[]{"EC"}, null, mock(SSLEngine.class))).isNull();
        assertThat(trace.requested.get()).isEqualTo(1);
        assertThat(trace.selected.get()).isZero();
    }
}
