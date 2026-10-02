package de.titus.simplycraft.controllers;

import de.titus.simplycraft.backup.SimplyfileClient;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class StatusController {

    private final SimplycraftProperties properties;
    private final SimplyfileClient simplyfile;

    public StatusController(SimplycraftProperties properties, SimplyfileClient simplyfile) {
        this.properties = properties;
        this.simplyfile = simplyfile;
    }

    /** Same health endpoint as Simplyfile */
    @GetMapping("/status")
    public ResponseEntity<Void> status() {
        return ResponseEntity.ok().build();
    }

    /** What the frontend needs to know about this installation. Reachable without token. */
    @GetMapping("/api/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("authRequired", properties.authRequired());
        info.put("customCommandEnabled", properties.customCommandEnabled());
        info.put("defaultXms", properties.defaultXms());
        info.put("defaultXmx", properties.defaultXmx());
        info.put("simplyfileEnabled", simplyfile.enabled());
        info.put("simplyfileUrl", simplyfile.enabled() ? properties.simplyfile().baseUrl() : null);
        return info;
    }
}
