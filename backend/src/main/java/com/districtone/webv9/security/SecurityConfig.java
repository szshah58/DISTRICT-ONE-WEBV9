package com.districtone.webv9.security;
import com.districtone.webv9.model.User;
import com.districtone.webv9.repo.UserRepository;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean PermissionAuthorizationFilter permissionAuthorizationFilter(UserPermissionService permissions) {
    return new PermissionAuthorizationFilter(permissions);
  }

  @Bean
  @Order(1)
  SecurityFilterChain initialAdminSetupChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(new AntPathRequestMatcher("/api/v1/auth/initial-admin", "POST"))
        .csrf(c -> c.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }

  @Bean UserDetailsService databaseUserDetailsService(UserRepository users) {
    return username -> {
      User user = users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException(username));
      String role = user.getRole() == null ? "STAFF" : user.getRole();
      return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
          .password(user.getPasswordHash())
          .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + role)))
          .disabled(!user.isActive())
          .build();
    };
  }

  @Bean
  @Order(2)
  SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                  PermissionAuthorizationFilter permissionFilter,
                                  CorsConfigurationSource corsConfigurationSource) throws Exception {
    return http.csrf(c -> c.disable()).cors(c -> c.configurationSource(corsConfigurationSource))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e
            .authenticationEntryPoint((request, response, exception) -> {
              logLedgerSecurityDenial(request, "SecurityFilterChain.anyRequest().authenticated()");
              new org.springframework.security.web.authentication.Http403ForbiddenEntryPoint()
                  .commence(request, response, exception);
            })
            .accessDeniedHandler((request, response, exception) -> {
              logLedgerSecurityDenial(request, "CustomerLedgerController.@PreAuthorize(hasRole(CUSTOMER))");
              new org.springframework.security.web.access.AccessDeniedHandlerImpl()
                  .handle(request, response, exception);
            }))
        .authorizeHttpRequests(a -> a.requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/initial-admin").permitAll()
            .requestMatchers("/api/v1/mobile-content/**").permitAll()
            .requestMatchers("/api/v1/auth/**", "/api/v1/registration/**", "/api/v1/system/database-verification", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(permissionFilter, JwtAuthenticationFilter.class).build();
  }

  @Bean CorsConfigurationSource corsConfigurationSource(@org.springframework.beans.factory.annotation.Value("${app.cors-origins}") String origins) {
    CorsConfiguration c = new CorsConfiguration();
    java.util.List<String> allowedOrigins = java.util.Arrays.stream(origins.split(","))
        .map(String::trim).filter(value -> !value.isEmpty()).toList();
    if (allowedOrigins.isEmpty()) {
      allowedOrigins = java.util.List.of("http://localhost:8080", "http://127.0.0.1:8080");
    }
    c.setAllowedOrigins(allowedOrigins);
    c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(java.util.List.of("*")); c.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c); return s;
  }

  // TEMPORARY: remove after one live WEBV9 ledger denial has been diagnosed.
  // Never include the request path, principal, JWT, headers, or request data in this log.
  private static final org.slf4j.Logger LEDGER_SECURITY_DIAGNOSTIC =
      org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

  private static void logLedgerSecurityDenial(jakarta.servlet.http.HttpServletRequest request,
                                              String denialComponent) {
    if (!"GET".equalsIgnoreCase(request.getMethod())
        || !request.getRequestURI().matches("/api/v1/customer/properties/[^/]+/ledger")) {
      return;
    }
    var authentication = org.springframework.security.core.context.SecurityContextHolder
        .getContext().getAuthentication();
    String type = authentication == null ? "none" : authentication.getClass().getSimpleName();
    String authorities = authentication == null ? "" : authentication.getAuthorities().stream()
        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .sorted().collect(java.util.stream.Collectors.joining(","));
    LEDGER_SECURITY_DIAGNOSTIC.warn(
        "TEMP customer-ledger 403 denialComponent={} authenticationExists={} authenticationType={} authorities=[{}]",
        denialComponent, authentication != null, type, authorities);
  }
}

