package de.titus.simplycraft.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stand-in for Mojang, Fabric, Modrinth and Simplyfile: answers fixed responses by path. */
public class FakeHttpServer implements AutoCloseable {

    public record Request(String method, String uri, Map<String, List<String>> headers, byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    private record Response(int status, byte[] body, String contentType) {
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Routes match the request path; with "?" in the key the full URI must match */
    public FakeHttpServer json(String path, String json) {
        routes.put(path, new Response(200, json.getBytes(StandardCharsets.UTF_8), "application/json"));
        return this;
    }

    public FakeHttpServer bytes(String path, byte[] body) {
        routes.put(path, new Response(200, body, "application/octet-stream"));
        return this;
    }

    public FakeHttpServer status(String path, int status) {
        routes.put(path, new Response(status, new byte[0], "text/plain"));
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String uri = exchange.getRequestURI().toString();
        requests.add(new Request(exchange.getRequestMethod(), uri, Map.copyOf(exchange.getRequestHeaders()), body));
        Response response = routes.getOrDefault(uri, routes.get(exchange.getRequestURI().getPath()));
        if (response == null) response = new Response(404, new byte[0], "text/plain");
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
        if (response.body().length > 0) exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
