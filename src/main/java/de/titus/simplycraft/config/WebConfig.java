package de.titus.simplycraft.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets other frontends of the ecosystem (e.g. synthia-frontend) talk to the API directly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SimplycraftProperties properties;

    public WebConfig(SimplycraftProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.corsAllowedOrigins();
        if (origins == null || origins.isEmpty()) return;

        String[] allowed = origins.stream().filter(o -> !o.isBlank()).toArray(String[]::new);
        if (allowed.length == 0) return;

        registry.addMapping("/api/**")
                .allowedOrigins(allowed)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("*");
        registry.addMapping("/status").allowedOrigins(allowed).allowedMethods("GET");
    }
}
