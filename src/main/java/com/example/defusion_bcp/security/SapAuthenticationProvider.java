package com.example.defusion_bcp.security;

import com.example.defusion_bcp.service.SapClient;
import com.example.defusion_bcp.service.SapSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SapAuthenticationProvider implements AuthenticationProvider {
    private static final Logger log = LoggerFactory.getLogger(SapAuthenticationProvider.class);
    private static final Set<String> ADMIN_USERS = Set.of("SIS37", "SIS21", "TES01", "TES03", "TES04");
    private static final Set<String> TREASURY_AREAS = Set.of("TES_LP", "TES_SC");

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
        boolean authenticated = false;
        try {
            boolean admin = ADMIN_USERS.contains(username.toUpperCase(Locale.ROOT));
            if (!admin) {
                SapClient.SapUserAccess user = sapClient.userAccess(sapSession, username)
                    .orElseThrow(() -> new DisabledException("Usuario SAP no habilitado para el portal"));
                if (!"P".equalsIgnoreCase(normalize(user.userType()))
                    || !TREASURY_AREAS.contains(normalize(user.area()).toUpperCase(Locale.ROOT))) {
                    log.warn("Acceso al portal rechazado para el usuario SAP {}", sanitize(username));
                    throw new DisabledException("Usuario SAP no habilitado para el portal");
                }
            }

            UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken.authenticated(
                username,
                null,
                admin
                    ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_TREASURY"),
                        new SimpleGrantedAuthority("FACTOR_SAP"))
                    : List.of(new SimpleGrantedAuthority("ROLE_TREASURY"), new SimpleGrantedAuthority("FACTOR_SAP"))
            );
            result.setDetails(sapSession);
            authenticated = true;
            log.info("Inicio de sesión SAP correcto para el usuario {} role={}", sanitize(username),
                admin ? "ADMIN" : "TREASURY");
            return result;
        } finally {
            if (!authenticated) sapClient.logoutQuietly(sapSession);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private String sanitize(String value) {
        return value.replaceAll("[\\r\\n\\t]", "_");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
