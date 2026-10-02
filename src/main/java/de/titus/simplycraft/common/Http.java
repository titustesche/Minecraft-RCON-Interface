package de.titus.simplycraft.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper around the JDK HttpClient for the external services (Mojang, Fabric,
 * Modrinth, Simplyfile). Downloads are streamed to disk and can be checked against a hash.
 */
@Component
public class Http {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ObjectMapper mapper;

    public Http(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public <T> T getJson(String url, Class<T> type, Map<String, String> headers) {
        return getJson(url, mapper.constructType(type), headers);
    }

    public <T> T getJson(String url, JavaType type, Map<String, String> headers) {
        HttpRequest request = request(url, headers).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw ApiException.notFound("Nicht gefunden: " + url);
            if (response.statusCode() / 100 != 2) {
                throw ApiException.badGateway("HTTP " + response.statusCode() + " von " + host(url), null);
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw ApiException.badGateway(host(url) + " ist nicht erreichbar", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway("Abgebrochen", e);
        }
    }

    /**
     * Downloads url to target. The file only appears at target once it is complete
     * (and matches sha1, when given).
     *
     * @return the number of bytes written
     */
    public long download(String url, Path target, String expectedSha1, Map<String, String> headers) {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        HttpRequest request = request(url, headers).timeout(Duration.ofMinutes(30)).GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw ApiException.badGateway("Download fehlgeschlagen: HTTP " + response.statusCode() + " von " + host(url), null);
                }
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                long size;
                try (var out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int read;
                    while ((read = body.read(buffer)) != -1) {
                        sha1.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        total += read;
                    }
                    size = total;
                }
                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    String actual = HexFormat.of().formatHex(sha1.digest());
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        throw ApiException.badGateway("Prüfsumme des Downloads stimmt nicht", null);
                    }
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return size;
            }
        } catch (IOException e) {
            throw ApiException.badGateway("Download von " + host(url) + " fehlgeschlagen: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway("Download abgebrochen", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    /** POSTs file as multipart/form-data field "field" and returns the response body. */
    public String postMultipart(String url, String field, Path file, String fileName, Map<String, String> headers) {
        String boundary = "simplycraft-" + UUID.randomUUID();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        try {
            HttpRequest request = request(url, headers)
                    .timeout(Duration.ofHours(2))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
                            HttpRequest.BodyPublishers.ofFile(file),
                            HttpRequest.BodyPublishers.ofString(tail, StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.badGateway("Upload fehlgeschlagen: HTTP " + response.statusCode() + " von " + host(url), null);
            }
            return response.body();
        } catch (IOException e) {
            throw ApiException.badGateway("Upload zu " + host(url) + " fehlgeschlagen: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway("Upload abgebrochen", e);
        }
    }

    private HttpRequest.Builder request(String url, Map<String, String> headers) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Ungültige URL: " + url);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw ApiException.badRequest("Nur http- und https-Links sind erlaubt");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if (headers != null) headers.forEach(builder::header);
        return builder;
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
