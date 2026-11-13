package com.backroom.access.security;

import com.backroom.access.AccessDtos;
import com.backroom.access.AccountPrincipal;
import com.backroom.access.PasswordResetService;
import com.backroom.access.PasswordValidator;
import com.backroom.shared.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http, ProblemResponses problems, ObjectMapper mapper, PasswordResetService resets)
            throws Exception {
        return http.csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.loginPage("/signin")
                        .loginProcessingUrl("/api/v1/auth/login")
                        .usernameParameter("email")
                        .successHandler((request, response, auth) -> {
                            var actor = (AccountPrincipal) auth.getPrincipal();
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setHeader("Cache-Control", "no-store");
                            mapper.writeValue(
                                    response.getOutputStream(),
                                    new AccessDtos.UserView(actor.userId(), actor.email(), actor.displayName()));
                        })
                        .failureHandler((request, response, error) -> problems.write(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "INVALID_CREDENTIALS",
                                "Email or password is incorrect.")))
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, error) -> problems.write(
                                        response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler((request, response, error) -> problems.write(
                                response,
                                HttpStatus.FORBIDDEN,
                                error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                error instanceof CsrfException
                                        ? "Your security token expired. Reload and try again."
                                        : "Your role does not permit this action.")))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/favicon.svg",
                                "/robots.txt",
                                "/site/**",
                                "/terms",
                                "/privacy",
                                "/security",
                                "/contact",
                                "/signin",
                                "/register",
                                "/invite",
                                "/reset",
                                "/app",
                                "/app/**",
                                "/s/*",
                                "/actuator/health",
                                "/actuator/health/readiness",
                                "/actuator/health/liveness",
                                "/api/v1/auth/csrf")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/password-reset",
                                "/api/v1/auth/password-reset/preview")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(new LoginInputFilter(problems), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new StaleSessionFilter(resets, problems),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .build();
    }

    /** Ends sessions that signed in before the account's password was reset. */
    private static class StaleSessionFilter extends OncePerRequestFilter {
        private final PasswordResetService resets;
        private final ProblemResponses problems;

        StaleSessionFilter(PasswordResetService resets, ProblemResponses problems) {
            this.resets = resets;
            this.problems = problems;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .getAuthentication();
            if (authentication != null
                    && authentication.getPrincipal() instanceof AccountPrincipal principal
                    && !resets.current(principal)) {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                // API calls learn why; pages load signed out, and the app then sends the person to sign in.
                if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                    problems.write(
                            response,
                            HttpStatus.UNAUTHORIZED,
                            "SESSION_ENDED",
                            "Your password was changed. Sign in again.");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    private static class LoginInputFilter extends OncePerRequestFilter {
        private final ProblemResponses problems;

        LoginInputFilter(ProblemResponses problems) {
            this.problems = problems;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getMethod().equals("POST")
                    || !request.getRequestURI().equals(request.getContextPath() + "/api/v1/auth/login");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var email = request.getParameter("email");
            if (email == null || email.length() > 254 || !PasswordValidator.valid(request.getParameter("password"))) {
                problems.write(
                        response, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
                return;
            }
            chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public String getParameter(String name) {
                            return name.equals("email")
                                    ? email.strip().toLowerCase(Locale.ROOT)
                                    : super.getParameter(name);
                        }
                    },
                    response);
        }
    }
}
