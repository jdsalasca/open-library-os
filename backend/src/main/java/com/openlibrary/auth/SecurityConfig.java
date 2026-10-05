package com.openlibrary.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;

/**
 * Session-based security on purpose: sessions live in Postgres, so a restart or
 * a crash never signs anyone out, and a user can be signed out by deleting rows.
 *
 * <p>Authorisation lives here, at the URL, instead of on each controller method.
 * A method-level annotation is only evaluated once a handler is matched, so a new
 * endpoint would be reachable by mistake until someone remembered the annotation.
 * Rules are ordered most specific first.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, PasswordGateFilter passwordGate) throws Exception {
        var csrfToken = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfToken.setCookiePath("/");

        // The client reads XSRF-TOKEN and echoes it in X-XSRF-TOKEN. That only works
        // with BREACH masking off: the default handler would demand a per-request
        // masked token and reject the very value it just put in the cookie.
        var rawCsrf = new CsrfTokenRequestAttributeHandler();
        rawCsrf.setCsrfRequestAttributeName(null);

        http
                // The SPA is a REST client: no server-rendered forms to protect.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfToken)
                        .csrfTokenRequestHandler(rawCsrf))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // New session id after login: defeats session fixation.
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/auth/csrf", "/auth/login").permitAll()

                        .requestMatchers(HttpMethod.GET, "/catalog/**").hasAuthority(Role.CATALOG_READ)
                        .requestMatchers("/catalog/**").hasAuthority(Role.CATALOG_WRITE)

                        .requestMatchers("/inventory/**").hasAuthority(Role.INVENTORY_WRITE)

                        // Order matters and is the whole point of matching by URL: a reader manages
                        // their own place in the queue, so this rule has to come before
                        // the staff-wide /loans/** rules that would answer 403.
                        .requestMatchers("/loans/reservations/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/loans/**").hasAuthority(Role.LOANS_OPERATE)
                        .requestMatchers("/loans/**").hasAuthority(Role.LOANS_OPERATE)
                        .requestMatchers("/reservations/**").authenticated()

                        // The map is how a reader finds a book on a phone, so it is
                        // open to any signed-in account; stocking shelves is not.
                        .requestMatchers(HttpMethod.GET, "/map").authenticated()

                        .requestMatchers(HttpMethod.PATCH, "/users/*/role").hasAuthority(Role.USERS_ROLES)
                        .requestMatchers(HttpMethod.GET, "/users/**").hasAuthority(Role.USERS_READ)
                        .requestMatchers("/users/**").hasAuthority(Role.USERS_WRITE)

                        .requestMatchers("/settings", "/settings/**", "/admin/**")
                        .hasAuthority(Role.SETTINGS_MANAGE)
                        .requestMatchers("/backup/**", "/export/**", "/import/**")
                        .hasAuthority(Role.BACKUP_MANAGE)

                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, HttpStatus.UNAUTHORIZED, "unauthenticated",
                                        "Sesion no iniciada o caducada."))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, HttpStatus.FORBIDDEN, "forbidden",
                                        "No tienes permiso para esta accion.")))
                .addFilterAfter(passwordGate, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeProblem(HttpServletResponse res, HttpStatus status, String code, String detail)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"code\":\"%s\",\"detail\":\"%s\"}"
                .formatted(status.value(), status.getReasonPhrase(), code, detail));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}