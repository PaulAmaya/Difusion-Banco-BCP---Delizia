package com.example.defusion_bcp.security;

import com.example.defusion_bcp.service.SapClient;
import com.example.defusion_bcp.service.SapSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SapAuthenticationProvider implements AuthenticationProvider {
    private static final Logger log = LoggerFactory.getLogger(SapAuthenticationProvider.class);

    private final SapClient sapClient;

    public SapAuthenticationProvider(SapClient sapClient) {
        this.sapClient = sapClient;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName() == null ? "" : authentication.getName().trim();
        String password = authentication.getCredentials() instanceof String value ? value : "";
        if (username.isBlank() || password.isBlank()) {
            throw new BadCredentialsException("Usuario y contraseña SAP son requeridos");
        }

        SapSession sapSession = sapClient.login(username, password);
        UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken.authenticated(
            username,
            null,
            List.of(
                new SimpleGrantedAuthority("ROLE_TREASURY"),
                new SimpleGrantedAuthority("FACTOR_SAP")
            )
        );
        result.setDetails(sapSession);
        log.info("Inicio de sesión SAP correcto para el usuario {}", sanitize(username));
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private String sanitize(String value) {
        return value.replaceAll("[\\r\\n\\t]", "_");
    }
}
