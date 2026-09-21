package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.SapProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SapClientTests {
    private HttpServer server;
    private SapProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        properties = new SapProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/b1s/v2");
        properties.setCompanyDb("TEST_DB");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void loginUsesV1EndpointAndKeepsSapCookies() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/b1s/v1/Login", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Set-Cookie", "B1SESSION=session-123; HttpOnly");
            exchange.getResponseHeaders().add("Set-Cookie", "ROUTEID=.node1; Path=/");
            send(exchange, 200, "{\"SessionId\":\"session-123\",\"Version\":\"10.0\",\"SessionTimeout\":25}");
        });

        SapSession session = new SapClient(properties).login("sap-user", "sap-password");

        assertThat(requestBody.get())
            .contains("\"CompanyDB\":\"TEST_DB\"")
            .contains("\"UserName\":\"sap-user\"")
            .contains("\"Password\":\"sap-password\"");
        assertThat(session.sessionId()).isEqualTo("session-123");
        assertThat(session.cookieHeader()).contains("B1SESSION=session-123", "ROUTEID=.node1");
        assertThat(session.companyDb()).isEqualTo("TEST_DB");
    }

    @Test
    void invalidSapCredentialsBecomeBadCredentials() {
        server.createContext("/b1s/v1/Login", exchange -> send(exchange, 401, "{\"error\":{}}"));

        assertThatThrownBy(() -> new SapClient(properties).login("wrong", "wrong"))
            .isInstanceOf(BadCredentialsException.class);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
