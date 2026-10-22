package org.figuramc.figuralink.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class EmbeddedFiguraServer {
    private static HttpServer server;
    private static final Map<String, String> authChallenges = new ConcurrentHashMap<>();
    private static final Map<String, String> playerTokens = new ConcurrentHashMap<>();
    private static final Path AVATARS_DIR = Paths.get("figura", "avatars");

    public static synchronized void start(int port) {
        if (server != null) return;
        try {
            Files.createDirectories(AVATARS_DIR);

            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool());

            // Root router
            server.createContext("/", new RootHandler());

            server.start();
            FiguraLink.LOGGER.info("[FiguraLimit] Embedded Figura Backend started on port: {}", port);
            FiguraLink.LOGGER.info("[FiguraLimit] Avatars will be stored in: {}", AVATARS_DIR.toAbsolutePath());
        } catch (java.net.BindException be) {
            FiguraLink.LOGGER.info("[FiguraLimit] Port {} is already in use by an active backend. Connecting to existing instance.", port);
        } catch (Exception e) {
            FiguraLink.LOGGER.error("[FiguraLimit] Failed to start embedded Figura Backend on port " + port, e);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            FiguraLink.LOGGER.info("[FiguraLimit] Embedded Figura Backend stopped.");
        }
    }

    private static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path == null) path = "/";
                
                // Clean leading /api or /api/
                while (path.startsWith("/api/")) {
                    path = path.substring(4);
                }
                if (path.equals("/api")) {
                    path = "/";
                }
                while (path.startsWith("//")) {
                    path = path.substring(1);
                }

                String method = exchange.getRequestMethod().toUpperCase();

                // 1. Auth ID: /auth/id?username=xxx
                if (path.equals("/auth/id")) {
                    String serverId = UUID.randomUUID().toString().replace("-", "") + "00000000";
                    if (serverId.length() > 40) serverId = serverId.substring(0, 40);
                    
                    String query = exchange.getRequestURI().getQuery();
                    String username = "player";
                    if (query != null && query.contains("username=")) {
                        for (String param : query.split("&")) {
                            if (param.startsWith("username=")) {
                                username = param.substring(9);
                                break;
                            }
                        }
                    }
                    authChallenges.put(serverId, username);
                    sendText(exchange, 200, serverId);
                    return;
                }

                // 2. Auth Verify: /auth/verify?id=xxx
                if (path.equals("/auth/verify")) {
                    String query = exchange.getRequestURI().getQuery();
                    String serverId = "";
                    if (query != null && query.contains("id=")) {
                        for (String param : query.split("&")) {
                            if (param.startsWith("id=")) {
                                serverId = param.substring(3);
                                break;
                            }
                        }
                    }

                    String username = authChallenges.getOrDefault(serverId, "player");
                    String token = UUID.randomUUID().toString();
                    playerTokens.put(token, username);

                    sendText(exchange, 200, token);
                    return;
                }

                // 3. Limits: /limits (Figura expects nested { "rate": { "upload": X, "download": Y }, "limits": { "maxAvatarSize": Z } })
                if (path.equals("/limits")) {
                    int maxBytes = FiguraLinkConfig.getMaxAvatarSize();
                    String json = "{\"rate\":{\"upload\":50,\"download\":200},\"limits\":{\"maxAvatarSize\":" + maxBytes + "}}";
                    sendJson(exchange, 200, json);
                    return;
                }

                // 4. Version: /version (Figura expects JSON { "release": "...", "prerelease": "..." })
                if (path.equals("/version")) {
                    String json = "{\"release\":\"0.1.6\",\"prerelease\":\"0.1.6\"}";
                    sendJson(exchange, 200, json);
                    return;
                }

                // 5. MOTD: /motd
                if (path.equals("/motd")) {
                    sendText(exchange, 200, "Figura Limit Unlocked Server");
                    return;
                }

                // 6. Equip: /equip
                if (path.equals("/equip") && method.equals("POST")) {
                    sendText(exchange, 200, "OK");
                    return;
                }

                // 7. Base check
                if (path.equals("/") || path.equals("/checkAuth")) {
                    sendText(exchange, 200, "OK");
                    return;
                }

                // 8. Upload Avatar: PUT /<avatarId> (e.g. PUT /avatar)
                String[] parts = path.split("/");
                if (method.equals("PUT") && parts.length == 2 && !parts[1].isEmpty()) {
                    String avatarId = parts[1];
                    String token = exchange.getRequestHeaders().getFirst("token");
                    String owner = (token != null && playerTokens.containsKey(token)) ? playerTokens.get(token) : "default";

                    byte[] data = exchange.getRequestBody().readAllBytes();
                    
                    if (data.length > FiguraLinkConfig.getMaxAvatarSize()) {
                        FiguraLink.LOGGER.warn("[FiguraLimit] Rejecting avatar: {} bytes > limit {} bytes", 
                                data.length, FiguraLinkConfig.getMaxAvatarSize());
                        sendText(exchange, 413, "Avatar is too big");
                        return;
                    }

                    if (FiguraLinkConfig.isTotalStorageExceeded(AVATARS_DIR)) {
                        FiguraLink.LOGGER.warn("[FiguraLimit] Rejecting avatar: Total server storage limit reached");
                        sendText(exchange, 507, "Server storage limit reached");
                        return;
                    }

                    Path userDir = AVATARS_DIR.resolve(owner);
                    Files.createDirectories(userDir);
                    Path avatarFile = userDir.resolve(avatarId);
                    Files.write(avatarFile, data);

                    FiguraLink.LOGGER.info("[FiguraLimit] Successfully saved avatar for '{}' ({}: {} bytes)", 
                            owner, avatarId, data.length);
                    sendText(exchange, 200, "OK");
                    return;
                }

                // 9. Download Avatar: GET /<owner>/<avatarId>
                if (method.equals("GET") && parts.length == 3) {
                    String owner = parts[1];
                    String avatarId = parts[2];
                    Path avatarFile = AVATARS_DIR.resolve(owner).resolve(avatarId);
                    if (Files.exists(avatarFile)) {
                        byte[] data = Files.readAllBytes(avatarFile);
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.sendResponseHeaders(200, data.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(data);
                        }
                        return;
                    } else {
                        sendText(exchange, 404, "Avatar not found");
                        return;
                    }
                }

                // 10. User Info: GET /<owner> (Figura expects { "equipped": [ { "owner": "...", "hash": "...", "id": "..." } ], "badges": { "regular": [], "special": [] } })
                if (method.equals("GET") && parts.length == 2 && !parts[1].isEmpty()) {
                    String owner = parts[1];
                    Path userDir = AVATARS_DIR.resolve(owner);
                    if (Files.exists(userDir) && Files.exists(userDir.resolve("avatar"))) {
                        String json = "{\"equipped\":[{\"owner\":\"" + owner + "\",\"hash\":\"avatar\",\"id\":\"avatar\"}],\"badges\":{\"regular\":[],\"special\":[]}}";
                        sendJson(exchange, 200, json);
                    } else {
                        String json = "{\"equipped\":[],\"badges\":{\"regular\":[],\"special\":[]}}";
                        sendJson(exchange, 200, json);
                    }
                    return;
                }

                // Default 200 OK
                sendText(exchange, 200, "OK");
            } catch (Exception e) {
                FiguraLink.LOGGER.error("[FiguraLimit] Error handling HTTP request", e);
                sendText(exchange, 500, "Internal Server Error");
            }
        }

        private void sendText(HttpExchange exchange, int code, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
