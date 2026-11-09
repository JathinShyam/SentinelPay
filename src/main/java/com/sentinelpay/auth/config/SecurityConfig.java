package com.sentinelpay.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.auth.api.UserView;
import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.auth.security.SecurityProblemWriter;
import com.sentinelpay.common.exception.ApiErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      ObjectMapper objectMapper,
                                                      SecurityProblemWriter problemWriter) throws Exception {
        http.securityMatcher("/api/**");

        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login",
                        "/api/v1/auth/register").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/reconciliation/**").hasRole("ADMIN")
                .anyRequest().authenticated());

        http.formLogin(form -> form
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getOutputStream(), UserView.from(user));
                })
                .failureHandler((request, response, exception) -> problemWriter.write(
                        request, response, ApiErrorCode.INVALID_CREDENTIALS,
                        "Invalid username or password"))
                .permitAll());

        http.logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((request, response, authentication) ->
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .invalidateHttpSession(true)
                .clearAuthentication(true));

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                        request, response, ApiErrorCode.UNAUTHENTICATED,
                        "Authentication is required"))
                .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                        request, response, ApiErrorCode.FORBIDDEN,
                        "You do not have permission to perform this action")));

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        http.requestCache(cache -> cache.disable());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .anyRequest().permitAll());
        http.csrf(csrf -> csrf.disable());
        return http.build();
    }
}
