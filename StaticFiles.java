package gym;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the website (HTML, CSS, JS) from the public folder. */
final class StaticFiles implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "jpg", "image/jpeg",
            "ico", "image/x-icon");

    private final Path root;

    StaticFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) { send(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8)); return; }

            String requested = ex.getRequestURI().getPath();
            if (requested.equals("/")) requested = "/index.html";
            Path file = root.resolve(requested.substring(1)).normalize();

            // Block ../ tricks that would read files outside the public folder.
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Page not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
        }
    }

    private void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
