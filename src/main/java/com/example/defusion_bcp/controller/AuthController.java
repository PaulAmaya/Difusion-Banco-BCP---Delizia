package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.AuthDtos;
import com.example.defusion_bcp.service.SapSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = new ChangeSessionIdAuthenticationStrategy();
    }

    @GetMapping("/csrf")
    public AuthDtos.CsrfResponse csrf(CsrfToken token) {
        return new AuthDtos.CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/login")
    public AuthDtos.CurrentUserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
        );
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return current(authentication);
    }

    @GetMapping("/me")
    public AuthDtos.CurrentUserResponse me(Authentication authentication) {
        return current(authentication);
    }

    private AuthDtos.CurrentUserResponse current(Authentication authentication) {
        SapSession sapSession = authentication.getDetails() instanceof SapSession details ? details : null;
        return new AuthDtos.CurrentUserResponse(
            authentication.getName(),
            authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
            sapSession == null ? "UNKNOWN" : "SAP",
            sapSession == null ? null : sapSession.companyDb(),
            sapSession == null ? null : sapSession.expiresAt()
        );
    }
}
