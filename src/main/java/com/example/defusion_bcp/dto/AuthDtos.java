package com.example.defusion_bcp.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {}

    public record CurrentUserResponse(
        String username,
        List<String> roles,
        String authenticationSource,
        String companyDb,
        java.time.Instant sapExpiresAt
    ) {}

    public record CsrfResponse(
        String headerName,
        String parameterName,
        String token
    ) {}
}
