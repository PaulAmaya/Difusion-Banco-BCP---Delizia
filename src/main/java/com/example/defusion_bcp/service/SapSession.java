package com.example.defusion_bcp.service;

import java.io.Serializable;
import java.time.Instant;
import java.time.Duration;

public final class SapSession implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String sessionId;
    private final String cookieHeader;
    private final String companyDb;
    private final String version;
    private final Duration idleTimeout;
    private volatile Instant expiresAt;
    public SapSession(String sessionId, String cookieHeader, Instant expiresAt, String companyDb, String version) {
        this.sessionId = sessionId; this.cookieHeader = cookieHeader;
        this.companyDb = companyDb; this.version = version;
        Instant now = Instant.now();
        Duration remoteTimeout = expiresAt == null ? Duration.ZERO : Duration.between(now, expiresAt);
        this.idleTimeout = remoteTimeout.compareTo(Duration.ofMinutes(30)) > 0 ? Duration.ofMinutes(30) : remoteTimeout;
        this.expiresAt = expiresAt == null ? now : now.plus(idleTimeout);
    }
    public String sessionId() { return sessionId; }
    public String cookieHeader() { return cookieHeader; }
    public String companyDb() { return companyDb; }
    public String version() { return version; }
    public Instant expiresAt() { return expiresAt; }
    public boolean expired(Instant now) { return !expiresAt.isAfter(now); }
    public synchronized void successfulQuery(Instant now) {
        // A request that finishes after expiry must never resurrect the session.
        if (!expired(now)) expiresAt = now.plus(idleTimeout);
    }
    public synchronized void expire() { expiresAt = Instant.EPOCH; }
}
