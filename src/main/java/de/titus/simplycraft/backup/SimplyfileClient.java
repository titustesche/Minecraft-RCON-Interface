package de.titus.simplycraft.backup;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.Http;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Uploads files to Simplyfile (POST /file/upload, multipart field "file").
 * The optional token is sent as "Authorization: Bearer ..." for the token-based uploads
 * planned in Simplyfile's roadmap (phase 2); today's Simplyfile simply ignores it.
 */
@Component
public class SimplyfileClient {

    private final Http http;
    private final SimplycraftProperties properties;

    public SimplyfileClient(Http http, SimplycraftProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    public record StoredFile(String id, String url) {
    }

    private record FileDTO(String id, String filename) {
    }

    public boolean enabled() {
        return properties.simplyfile() != null && properties.simplyfile().enabled();
    }

    public StoredFile upload(Path file, String fileName) {
        if (!enabled()) throw ApiException.badRequest("Simplyfile ist nicht konfiguriert (simplycraft.simplyfile.url)");
        String base = properties.simplyfile().baseUrl();
        Map<String, String> headers = new HashMap<>();
        String token = properties.simplyfile().token();
        if (token != null && !token.isBlank()) headers.put("Authorization", "Bearer " + token);

        String body = http.postMultipart(base + "/file/upload", "file", file, fileName, headers);
        FileDTO dto;
        try {
            dto = http.mapper().readValue(body, FileDTO.class);
        } catch (RuntimeException e) {
            throw ApiException.badGateway("Unerwartete Antwort von Simplyfile", e);
        }
        if (dto.id() == null) throw ApiException.badGateway("Simplyfile hat keine Datei-ID geliefert", null);
        return new StoredFile(dto.id(), base + "/file/" + dto.id());
    }
}
