package com.example.defusion_bcp.service;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BankTlsKeyManager extends X509ExtendedKeyManager {
    private static final Logger log = LoggerFactory.getLogger(BankTlsKeyManager.class);
    private final X509ExtendedKeyManager delegate;
    private final String requestId;
    private final Trace trace;

    static final class Trace {
        final AtomicInteger requested = new AtomicInteger();
        final AtomicInteger selected = new AtomicInteger();
    }

    BankTlsKeyManager(X509ExtendedKeyManager delegate, String requestId, Trace trace) {
        this.delegate = delegate;
        this.requestId = requestId;
        this.trace = trace;
    }

    private String observe(String alias, String[] keyTypes, Principal[] issuers) {
        trace.requested.incrementAndGet();
        if (alias != null) trace.selected.incrementAndGet();
        log.info("BCP_TLS_CLIENT_CERT requestId={} selected={} keyTypes={} issuerCount={}", requestId,
            alias != null, BankNetworkDiagnostics.redact(java.util.Arrays.toString(keyTypes)), issuers == null ? 0 : issuers.length);
        return alias;
    }

    @Override public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) {
        return observe(delegate.chooseEngineClientAlias(types, issuers, engine), types, issuers);
    }
    @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
        return observe(delegate.chooseClientAlias(types, issuers, socket), types, issuers);
    }
    @Override public String[] getClientAliases(String type, Principal[] issuers) { return delegate.getClientAliases(type, issuers); }
    @Override public String[] getServerAliases(String type, Principal[] issuers) { return delegate.getServerAliases(type, issuers); }
    @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return delegate.chooseServerAlias(type, issuers, socket); }
    @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return delegate.chooseEngineServerAlias(type, issuers, engine); }
    @Override public X509Certificate[] getCertificateChain(String alias) { return delegate.getCertificateChain(alias); }
    @Override public PrivateKey getPrivateKey(String alias) { return delegate.getPrivateKey(alias); }
}
