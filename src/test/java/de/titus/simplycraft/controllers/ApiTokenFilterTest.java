package de.titus.simplycraft.controllers;

import de.titus.simplycraft.config.ApiTokenFilter;
import de.titus.simplycraft.config.SimplycraftProperties;
import de.titus.simplycraft.server.ServerServiceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApiTokenFilterTest {

    @TempDir
    Path root;

    private int status(String token, String uri, String header, String query) throws Exception {
        SimplycraftProperties base = ServerServiceTest.properties(root, true);
        SimplycraftProperties properties = new SimplycraftProperties(base.serversPath(), base.backupsPath(), base.javaPath(),
                base.defaultXms(), base.defaultXmx(), base.consoleBacklog(), base.stopTimeout(), token, true,
                base.corsAllowedOrigins(), base.simplyfile(), base.modrinth(), base.sources());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (header != null) request.addHeader("Authorization", header);
        if (query != null) request.setParameter("token", query);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiTokenFilter(properties).doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void openWithoutConfiguredToken() throws Exception {
        assertEquals(200, status("", "/api/servers", null, null));
    }

    @Test
    void protectsApiWhenTokenIsSet() throws Exception {
        assertEquals(401, status("secret", "/api/servers", null, null));
        assertEquals(401, status("secret", "/api/servers", "Bearer wrong", null));
        assertEquals(200, status("secret", "/api/servers", "Bearer secret", null));
        assertEquals(200, status("secret", "/api/servers/x/console", null, "secret"));
    }

    @Test
    void frontendStatusAndInfoStayReachable() throws Exception {
        assertEquals(200, status("secret", "/index.html", null, null));
        assertEquals(200, status("secret", "/status", null, null));
        assertEquals(200, status("secret", "/api/info", null, null));
    }
}
