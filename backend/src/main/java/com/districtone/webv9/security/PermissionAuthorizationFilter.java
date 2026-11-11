package com.districtone.webv9.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Enforces the same active designation permissions used by login and user administration. */
public class PermissionAuthorizationFilter extends OncePerRequestFilter {
  private static final Map<String, String> MODULES = Map.ofEntries(
      Map.entry("/api/v1/admin/users", "Users & Roles"),
      Map.entry("/api/v1/admin/positions", "Users & Roles"),
      Map.entry("/api/v1/admin/customer-import", "Admin & Settings"),
      Map.entry("/api/v1/admin/mobile-content", "Admin & Settings"),
      Map.entry("/api/v1/admin/config/audit/log", "System Logs"),
      Map.entry("/api/v1/admin/config", "Admin & Settings"),
      Map.entry("/api/v1/dashboard", "Dashboard"),
      Map.entry("/api/v1/registration", "Members"),
      Map.entry("/api/v1/sales", "Plots & Sales"),
      Map.entry("/api/v1/plots", "Plots & Sales"),
      Map.entry("/api/v1/projects", "Plots & Sales"),
      Map.entry("/api/v1/customers", "Members"),
      Map.entry("/api/v1/members", "Members"),
      Map.entry("/api/v1/finance", "Finance"),
      Map.entry("/api/v1/payments", "Finance"),
      Map.entry("/api/v1/customer-care", "Customer Care"),
      Map.entry("/api/v1/documents", "Documents"),
      Map.entry("/api/v1/reports", "Reports"),
      Map.entry("/api/v1/audit", "System Logs"));

  private final UserPermissionService permissions;

  public PermissionAuthorizationFilter(UserPermissionService permissions) {
    this.permissions = permissions;
  }

  private boolean requiresSystemWideAccess(String path, String module, String method) {
    return "Dashboard".equals(module)
        || "System Logs".equals(module)
        || "Admin & Settings".equals(module)
        || path.matches("/api/v1/admin/positions/\\d+/permissions")
        || path.equals("/api/v1/admin/positions") && !"GET".equalsIgnoreCase(method)
        || path.startsWith("/api/v1/finance/accounts")
        || path.startsWith("/api/v1/customer-care/mobile-content")
        || path.startsWith("/api/v1/customer-care/announcements");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());

    if ("OPTIONS".equalsIgnoreCase(request.getMethod())
        || path.startsWith("/api/v1/auth/")
        || path.equals("/api/v1/system/database-verification")
        || path.startsWith("/api/v1/mobile-content/")) {
      chain.doFilter(request, response);
      return;
    }

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getPrincipal())) {
      chain.doFilter(request, response);
      return;
    }
    boolean customerApi = path.equals("/api/v1/customer") || path.startsWith("/api/v1/customer/");
    boolean customerIdentity = authentication.getName().startsWith("customer:");
    if (customerApi) {
      if (!customerIdentity) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN,
            "Customer endpoints require a customer account.");
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (customerIdentity) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN,
          "Customer accounts cannot access staff APIs.");
      return;
    }

    String module = moduleFor(path);
    if (module == null) {
      if (path.startsWith("/api/v1/")) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "No authorization policy is configured for this API.");
        return;
      }
      chain.doFilter(request, response);
      return;
    }

    if (requiresSystemWideAccess(path, module, request.getMethod())
        && !permissions.isSuperAdmin(authentication.getName())) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN,
          "This operation is system-wide and requires Super Admin access.");
      return;
    }

    for (String action : actionsFor(request.getMethod(), path, module)) {
      if (!permissions.isGranted(authentication.getName(), module, action)) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Required permission is not assigned: " + action);
        return;
      }
    }
    String reportModule = reportDataModule(path);
    if (reportModule != null && !permissions.isGranted(authentication.getName(), reportModule, "VIEW")) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN,
          "Required report source permission is not assigned: VIEW");
      return;
    }
    chain.doFilter(request, response);
  }

  private String reportDataModule(String path) {
    if (path.startsWith("/api/v1/reports/finance/")) return "Finance";
    if (path.startsWith("/api/v1/reports/sales/") || path.startsWith("/api/v1/reports/plots/"))
      return "Plots & Sales";
    return null;
  }

  private String moduleFor(String path) {
    return MODULES.entrySet().stream()
        .filter(entry -> path.equals(entry.getKey()) || path.startsWith(entry.getKey() + "/"))
        .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
        .map(Map.Entry::getValue)
        .orElse(null);
  }

  private java.util.List<String> actionsFor(String method, String path, String module) {
    if ("Finance".equals(module)) {
      if ("POST".equalsIgnoreCase(method) && path.matches("/api/v1/finance/ledger/report/?"))
        return java.util.List.of("VIEW");
      if ("POST".equalsIgnoreCase(method) && (path.matches(".*/payments/recovery/?$")
          || path.matches(".*/finance/recovery/?$") || path.matches("/api/v1/payments/?$")))
        return java.util.List.of("RECEIPT_CREATE");
      if (path.matches(".*/payments/\\d+/(verify|approve|reject)/?$")) return java.util.List.of("RECEIPT_VERIFY");
      if (path.matches(".*/payments/\\d+/post/?$")) return java.util.List.of("RECEIPT_POST");
      if (path.matches(".*/payments/\\d+/reverse/?$") || path.matches(".*/finance/payments/\\d+/reverse/?$"))
        return java.util.List.of("RECEIPT_REVERSE");
    }
    return java.util.List.of(actionFor(method, path));
  }

  private String actionFor(String method, String path) {
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) return "VIEW";
    if (path.matches(".*/(verify|approve|post|reject|disburse)(/.*)?$")) return "APPROVE";
    if ("DELETE".equalsIgnoreCase(method)) return "DELETE";
    if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) return "EDIT";
    return "ADD";
  }
}
