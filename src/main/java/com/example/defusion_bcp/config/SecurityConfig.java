package com.example.defusion_bcp.config;

import com.example.defusion_bcp.security.SapAuthenticationProvider;
import com.example.defusion_bcp.security.SapIdleSessionFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import com.example.defusion_bcp.service.SapClient;
import com.example.defusion_bcp.service.SapSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    AuthenticationManager authenticationManager(SapAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository securityContextRepository,
                                            SapClient sapClient) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");

        http
            .addFilterBefore(new SapIdleSessionFilter(), CsrfFilter.class)
            .cors(cors -> {})
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/csrf", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"AUTH_REQUIRED\",\"message\":\"Inicie sesion nuevamente\"}");
            }))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .addLogoutHandler((request, response, authentication) -> {
                    if (authentication != null && authentication.getDetails() instanceof SapSession sapSession) {
                        sapClient.logoutQuietly(sapSession);
                    }
                })
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
            );
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${app.security.allowed-origin}") String allowedOrigin
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-SAP-Expires-At"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
