package com.sinosoft.amp.openapi.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * 双轨鉴权 HTTP 层。
 *
 * <p>{@code amp.iam.issuer-uri} 为空时：不启用 JWT（仅 API Key，兼容一期）。
 * 配置后：JWT 走 OAuth2 Resource Server；API Key 仍由 {@code ApiKeyAuthFilter} 处理。
 * 业务授权语义由 {@code RbacGuard}/{@code AuthContext} 承担，此处不拦业务路径。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({IamProperties.class, AuthProperties.class})
public class AmpSecurityConfig {

    @Bean
    public SecurityFilterChain ampSecurityFilterChain(HttpSecurity http, AuthProperties authProperties, IamProperties iamProperties)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        writeError(response, 401, "INVALID_TOKEN", authException.getMessage())));

        if (iamProperties.jwtEnabled()) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .bearerTokenResolver(new ApiKeyAwareBearerTokenResolver(authProperties.byKey().keySet()))
                    .authenticationEntryPoint((request, response, authException) ->
                            writeError(response, 401, "INVALID_TOKEN", authException.getMessage()))
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        }

        // 鉴权由 ApiKeyAuthFilter + RbacGuard/AuthContext 完成；HTTP 层放行，仅做 token 合法性
        http.authorizeHttpRequests(authz -> authz.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    @Configuration
    @ConditionalOnExpression("'${amp.iam.issuer-uri:}'.length() > 0 || '${amp.iam.jwk-set-uri:}'.length() > 0")
    static class JwtDecoderConfiguration {

        @Bean
        public JwtDecoder jwtDecoder(IamProperties iamProperties) {
            if (iamProperties.jwkSetUri() != null && !iamProperties.jwkSetUri().isBlank()) {
                return NimbusJwtDecoder.withJwkSetUri(iamProperties.jwkSetUri()).build();
            }
            return NimbusJwtDecoder.withIssuerLocation(iamProperties.issuerUri()).build();
        }
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String safeMessage = message == null ? "unauthorized" : message.replace("\"", "'");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + safeMessage + "\"}");
    }
}
