package com.pnomeer.lab.app.dashboard;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;

/** Only GET view routes opt into migration. API bodies and streams stay in the servlet pipeline. */
@Component
public class DashboardRoutingFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DashboardRoutingFilter.class);
    private final boolean redirect;
    private final String origin;
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardRoutingFilter(@Value("${lab.dashboard.redirect-enabled:true}") boolean redirect,
            @Value("${lab.dashboard.frontend-origin:}") String origin) {
        this(redirect, origin, new org.springframework.core.io.ClassPathResource("static/app/index.html").exists());
    }
    /**
     * Redirects need a destination: a standalone frontend origin or the dashboard embedded in this jar.
     * An API-only build (-PskipFrontend, bootRun) keeps serving the legacy screens instead of 404s.
     */
    DashboardRoutingFilter(boolean redirect, String origin, boolean embedded) {
        this.redirect = redirect && (!origin.isBlank() || embedded);
        if (redirect && !this.redirect)
            log.warn("stage=dashboard reason=legacy redirect requested but no embedded dashboard or frontend-origin; legacy screens stay");
        if (!origin.isBlank()) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException("lab.dashboard.frontend-origin must be an HTTP(S) origin without a path");
        }
        this.origin = origin.isBlank() ? "" : origin.replaceAll("/$", "");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            String target = path.substring(4);
            String decoded = java.net.URLDecoder.decode(target, java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.contains("\\") || java.util.Arrays.stream(decoded.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))) {
                response.sendError(404); return;
            }
            // A prefix alias, not an unrestricted proxy or a nested dispatcher.
            if (!(target.equals("/runs") || target.startsWith("/runs/") || target.equals("/experiments")
                    || target.startsWith("/experiments/") || target.equals("/modules") || target.startsWith("/ingest/")
                    || target.startsWith("/lab/io/") || target.startsWith("/dashboard/v1/")
                    || target.equals("/run-series") || target.startsWith("/run-series/")
                    || target.equals("/run-sweeps") || target.startsWith("/run-sweeps/")
                    || target.equals("/evidence-bundles") || target.startsWith("/maintenance/"))) {
                response.sendError(404); return;
            }
            var wrapped = new HttpServletResponseWrapper(response) {
                private String location(String name, String value) {
                    return name.equalsIgnoreCase("Location") && value.startsWith("/") && !value.startsWith("//") ? "/api" + value : value;
                }
                @Override public void setHeader(String name, String value) { super.setHeader(name, location(name, value)); }
                @Override public void addHeader(String name, String value) { super.addHeader(name, location(name, value)); }
            };
            request.getRequestDispatcher(target).forward(request, wrapped);
            return;
        }
        if (redirect && request.getMethod().equals("GET") && !"true".equals(request.getParameter("legacy"))) {
            String destination = destination(path, request);
            if (destination != null) {
                var uri = UriComponentsBuilder.fromUriString(origin + destination);
                request.getParameterMap().forEach((key, values) -> {
                    if (!key.equals("legacy") && !(path.equals("/runs/ui") && key.equals("run")))
                        for (String value : values) uri.queryParam(key, value);
                });
                response.setStatus(302); response.setHeader("Cache-Control", "no-store");
                response.setHeader("Location", uri.build().encode().toUriString()); return;
            }
        }
        chain.doFilter(request, response);
    }
    private String destination(String path, HttpServletRequest request) {
        return switch (path) {
            case "/", "/ui" -> "/app/runs";
            case "/experiments/ui" -> "/app/experiments";
            case "/ingest/ui" -> "/app/experiments/pipeline.excel-ingestion";
            case "/ingest/ui/db" -> "/app/tools/ingestion-db";
            case "/lab/io/ui" -> "/app/tools/io-echo";
            case "/runs/ui" -> request.getParameter("run") != null && !request.getParameter("run").isBlank()
                    ? "/app/runs/" + safeId(request.getParameter("run"))
                    : request.getParameter("baseline") != null && request.getParameter("candidate") != null ? "/app/compare" : "/app/runs";
            default -> path.matches("/(experiments|ingest)/ui/[a-zA-Z0-9._-]+")
                    ? "/app/runs/" + path.substring(path.lastIndexOf('/') + 1) : null;
        };
    }
    private String safeId(String id) {
        // Run IDs are UUIDs; malformed view bookmarks remain an explicit missing Run.
        return id.matches("[a-zA-Z0-9._-]{1,200}") ? id : "invalid-run-id";
    }
}
