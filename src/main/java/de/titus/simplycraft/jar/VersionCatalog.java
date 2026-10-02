package de.titus.simplycraft.jar;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.common.Http;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Version lists of Mojang and Fabric, cached for a few minutes */
@Component
public class VersionCatalog {

    private static final Duration CACHE = Duration.ofMinutes(10);

    private final Http http;
    private final SimplycraftProperties properties;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public VersionCatalog(Http http, SimplycraftProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    public List<MinecraftVersion> versions(ServerType type, boolean snapshots) {
        List<MinecraftVersion> all = switch (type) {
            case VANILLA -> mojangManifest().versions().stream()
                    .map(v -> new MinecraftVersion(v.id(), v.type()))
                    .toList();
            case FABRIC -> fabricGames().stream()
                    .map(v -> new MinecraftVersion(v.version(), v.stable() ? "release" : "snapshot"))
                    .toList();
        };
        return all.stream()
                .filter(v -> "release".equals(v.type()) || (snapshots && "snapshot".equals(v.type())))
                .toList();
    }

    ExternalModels.MojangManifest mojangManifest() {
        return cached("mojang", () -> http.getJson(properties.sources().mojangManifest(), ExternalModels.MojangManifest.class, Map.of()));
    }

    ExternalModels.MojangManifest.Entry mojangVersion(String version) {
        return mojangManifest().versions().stream()
                .filter(v -> v.id().equals(version))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unbekannte Minecraft-Version " + version));
    }

    List<ExternalModels.FabricGame> fabricGames() {
        return cached("fabric-game", () -> http.getJson(fabricMeta() + "/versions/game", listOf(ExternalModels.FabricGame.class), Map.of()));
    }

    String latestFabricLoader() {
        return latestStable("fabric-loader", "/versions/loader");
    }

    String latestFabricInstaller() {
        return latestStable("fabric-installer", "/versions/installer");
    }

    String fabricMeta() {
        String url = properties.sources().fabricMeta();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String latestStable(String key, String path) {
        List<ExternalModels.FabricComponent> components = cached(key,
                () -> http.getJson(fabricMeta() + path, listOf(ExternalModels.FabricComponent.class), Map.of()));
        return components.stream()
                .filter(ExternalModels.FabricComponent::stable)
                .findFirst()
                .or(() -> components.stream().findFirst())
                .map(ExternalModels.FabricComponent::version)
                .orElseThrow(() -> ApiException.badGateway("Fabric liefert keine Versionen", null));
    }

    private JavaType listOf(Class<?> type) {
        return http.mapper().getTypeFactory().constructCollectionType(List.class, type);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        Cached<?> entry = cache.get(key);
        if (entry != null && entry.until().isAfter(Instant.now())) return (T) entry.value();
        T value = loader.get();
        cache.put(key, new Cached<>(value, Instant.now().plus(CACHE)));
        return value;
    }

    private record Cached<T>(T value, Instant until) {
    }
}
