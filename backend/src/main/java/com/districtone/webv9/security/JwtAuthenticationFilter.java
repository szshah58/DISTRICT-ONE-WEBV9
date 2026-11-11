package com.districtone.webv9.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private static final String AUTH_COOKIE = "WEBV9_AUTH";
  private final JwtService jwtService;
  private final JdbcTemplate jdbc;
  private final UserPermissionService permissions;
  public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc, UserPermissionService permissions) {
    this.jwtService = jwtService;
    this.jdbc = jdbc;
    this.permissions = permissions;
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    boolean fromCookie = false;
    if (token == null && request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (AUTH_COOKIE.equals(cookie.getName())) {
          token = cookie.getValue();
          fromCookie = true;
          break;
        }
      }
    }
    if (token != null && !token.isBlank()) {
      try {
        Claims claims = jwtService.parse(token);
        String tokenRole = claims.get("role", String.class);
        if (claims.getSubject() == null || claims.getSubject().isBlank() || tokenRole == null || tokenRole.isBlank()) {
          throw new IllegalArgumentException("JWT is missing required authentication claims");
        }
        if (claims.getSubject().startsWith("customer:")) {
          var customerAuth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
              List.of(new SimpleGrantedAuthority("ROLE_" + tokenRole.trim().toUpperCase(Locale.ROOT))));
          SecurityContextHolder.getContext().setAuthentication(customerAuth);
          chain.doFilter(request, response);
          return;
        }
        if (!permissions.isAccountActive(claims.getSubject())) {
          throw new IllegalArgumentException("Account has no current authorization assignment");
        }
        var account = jdbc.queryForMap("""
            SELECT u.role
            FROM users u
            WHERE u.username = ? AND u.is_active = 1
            """, claims.getSubject());
        String role = String.valueOf(account.get("role"));
        var authorities = new java.util.LinkedHashSet<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(Locale.ROOT)));
        if (permissions.isSuperAdmin(claims.getSubject()))
          authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
            List.copyOf(authorities));
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (RuntimeException e) {
        if (!fromCookie) {
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          return;
        }
        response.addHeader("Set-Cookie", ResponseCookie.from(AUTH_COOKIE, "")
            .httpOnly(true)
            .secure(request.isSecure() || "localhost".equalsIgnoreCase(request.getServerName())
                || "127.0.0.1".equals(request.getServerName()) || "::1".equals(request.getServerName()))
            .sameSite("Lax")
            .path("/api/v1")
            .maxAge(0)
            .build().toString());
      }
    }
    chain.doFilter(request, response);
  }
}
