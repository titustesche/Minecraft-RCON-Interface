package de.titus.simplycraft.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional shared secret for the API. Starting servers means running arbitrary jars,
 * so as soon as the console is reachable by others, simplycraft.api-token must be set.
 * <p>
 * The token is accepted as "Authorization: Bearer ..." or as ?token=... (EventSource and
 * download links cannot set headers).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private final SimplycraftProperties properties;

    public ApiTokenFilter(SimplycraftProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.authRequired()
                || !request.getRequestURI().startsWith("/api/")
                || request.getRequestURI().equals("/api/info")
                || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(extractToken(request))) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"API-Token fehlt oder ist ungültig\"}");
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        return request.getParameter("token");
    }

    private boolean matches(String token) {
        if (token == null) return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                properties.apiToken().getBytes(StandardCharsets.UTF_8)
        );
    }
}
