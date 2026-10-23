package org.figuramc.figuralink.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmbeddedFiguraServer {
    private static HttpServer server;
    private static final Map<String, String> authChallenges = new ConcurrentHashMap<>();
    private static final Map<String, String> playerTokens = new ConcurrentHashMap<>();
    private static final Map<String, String> userEquipped = new ConcurrentHashMap<>();
    private static final Path AVATARS_DIR = Paths.get("figura", "avatars");
    private static final Path EQUIPPED_FILE = AVATARS_DIR.resolve("equipped.properties");

    public static synchronized void start(int port) {
        if (server != null) return;
        try {
            Files.createDirectories(AVATARS_DIR);
            loadEquippedState();

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

    private static String getSha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "avatar";
        }
    }

    private static String getSha256(Path file) {
        try {
            return getSha256(Files.readAllBytes(file));
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void loadEquippedState() {
        try {
            if (Files.exists(EQUIPPED_FILE)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(EQUIPPED_FILE)) {
                    props.load(in);
                    for (String name : props.stringPropertyNames()) {
                        userEquipped.put(name, props.getProperty(name));
                    }
                }
            }
            // Also scan directories in AVATARS_DIR for any user avatars
            if (Files.exists(AVATARS_DIR)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(AVATARS_DIR, Files::isDirectory)) {
                    for (Path p : ds) {
                        String name = p.getFileName().toString();
                        if (!name.equals("default") && (Files.exists(p.resolve("avatar")))) {
                            String current = userEquipped.get(name);
                            if (current == null || current.equals("avatar") || current.length() < 32) {
                                String hash = getSha256(p.resolve("avatar"));
                                if (hash != null) {
                                    userEquipped.put(name, hash);
                                } else {
                                    userEquipped.put(name, "avatar");
                                }
                            }
                        }
                    }
                }
            }
            FiguraLink.LOGGER.info("[FiguraLimit] Loaded {} equipped avatar entries.", userEquipped.size());
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not load equipped state: " + t.getMessage());
        }
    }

    private static synchronized void saveEquippedState() {
        try {
            Properties props = new Properties();
            for (Map.Entry<String, String> entry : userEquipped.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue());
            }
            try (OutputStream out = Files.newOutputStream(EQUIPPED_FILE)) {
                props.store(out, "Figura Limit Unlocked Equipped Avatars");
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not save equipped state: " + t.getMessage());
        }
    }

    public static void setUserEquipped(String owner, String hash) {
        if (owner != null && hash != null) {
            userEquipped.put(owner, hash);
            saveEquippedState();
        }
    }

    public static String getUserEquipped(String owner) {
        return userEquipped.get(owner);
    }

    public static Map<String, String> getUserEquippedMap() {
        return java.util.Collections.unmodifiableMap(userEquipped);
    }

    public static Path getAvatarsDir() {
        return AVATARS_DIR;
    }

    public static boolean saveAvatarFile(String owner, String hash, byte[] data) {
        try {
            if (owner == null || data == null || data.length == 0) return false;
            Path userDir = AVATARS_DIR.resolve(owner);
            Files.createDirectories(userDir);
            Files.write(userDir.resolve("avatar"), data);
            if (hash != null && !hash.isEmpty() && !hash.equals("avatar")) {
                Files.write(userDir.resolve(hash), data);
                userEquipped.put(owner, hash);
            } else {
                String computed = getSha256(data);
                Files.write(userDir.resolve(computed), data);
                userEquipped.put(owner, computed);
            }
            saveEquippedState();
            FiguraLink.LOGGER.info("[FiguraLimit] Stored avatar for {} (size: {} bytes, hash: {})", 
                    owner, data.length, userEquipped.get(owner));
            return true;
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not store avatar for {}: {}", owner, t.getMessage());
            return false;
        }
    }

    public static byte[] getAvatarBytes(String owner) {
        try {
            if (owner == null) return null;
            Path userDir = AVATARS_DIR.resolve(owner);
            Path avatarFile = userDir.resolve("avatar");
            if (Files.exists(avatarFile)) {
                return Files.readAllBytes(avatarFile);
            }
            String hash = userEquipped.get(owner);
            if (hash != null && Files.exists(userDir.resolve(hash))) {
                return Files.readAllBytes(userDir.resolve(hash));
            }
            Path defaultFile = AVATARS_DIR.resolve("default").resolve("avatar");
            if (Files.exists(defaultFile)) {
                return Files.readAllBytes(defaultFile);
            }
        } catch (Throwable ignored) {}
        return null;
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

                // 3. Limits: /limits
                if (path.equals("/limits")) {
                    int maxBytes = FiguraLinkConfig.getMaxAvatarSize();
                    String json = "{\"rate\":{\"upload\":50,\"download\":200},\"limits\":{\"maxAvatarSize\":" + maxBytes + "}}";
                    sendJson(exchange, 200, json);
                    return;
                }

                // 4. Version: /version
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

                // 6. Equip: /equip (POST [{"owner":"<uuid>","id":"avatar"}])
                if (path.equals("/equip") && method.equals("POST")) {
                    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                    String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8).trim();
                    FiguraLink.LOGGER.info("[FiguraLimit] Received /equip payload: {}", bodyStr);

                    Matcher mOwner = Pattern.compile("\"owner\"\\s*:\\s*\"([^\"]+)\"").matcher(bodyStr);
                    Matcher mId = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(bodyStr);

                    if (mOwner.find()) {
                        String owner = mOwner.group(1);
                        String id = mId.find() ? mId.group(1) : "avatar";

                        Path ownerDir = AVATARS_DIR.resolve(owner);
                        Files.createDirectories(ownerDir);
                        Path targetFile = ownerDir.resolve(id);

                        // If not found in owner's dir, copy from default if available
                        if (!Files.exists(targetFile) && !Files.exists(ownerDir.resolve("avatar"))) {
                            Path defaultFile = AVATARS_DIR.resolve("default").resolve(id);
                            if (!Files.exists(defaultFile)) {
                                defaultFile = AVATARS_DIR.resolve("default").resolve("avatar");
                            }
                            if (Files.exists(defaultFile)) {
                                Files.copy(defaultFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                                Files.copy(defaultFile, ownerDir.resolve("avatar"), StandardCopyOption.REPLACE_EXISTING);
                            }
                        }

                        String existing = userEquipped.get(owner);
                        if (existing == null || existing.equals("avatar") || existing.length() < 32) {
                            Path avatarFile = Files.exists(ownerDir.resolve("avatar")) ? ownerDir.resolve("avatar") : targetFile;
                            if (Files.exists(avatarFile)) {
                                String hash = getSha256(avatarFile);
                                if (hash != null) {
                                    Files.copy(avatarFile, ownerDir.resolve(hash), StandardCopyOption.REPLACE_EXISTING);
                                    userEquipped.put(owner, hash);
                                } else {
                                    userEquipped.put(owner, "avatar");
                                }
                            } else {
                                userEquipped.put(owner, id);
                            }
                        }

                        saveEquippedState();
                        if (FiguraLinkConfig.isClientEnvironment()) {
                            org.figuramc.figuralink.client.FiguraClientSync.onLocalAvatarEquipped(owner);
                        } else {
                            FiguraServerSync.broadcastAvatarUpdate(owner);
                        }
                    }
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
                    String owner = "default";
                    if (token != null && !token.trim().isEmpty() && !token.equals("default") && !token.equals("figuralink-token")) {
                        owner = token.trim();
                    } else if (token != null && playerTokens.containsKey(token)) {
                        owner = playerTokens.get(token);
                    }

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

                    String hash = getSha256(data);

                    // Save under owner's folder
                    Path userDir = AVATARS_DIR.resolve(owner);
                    Files.createDirectories(userDir);
                    Files.write(userDir.resolve(avatarId), data);
                    Files.write(userDir.resolve("avatar"), data);
                    Files.write(userDir.resolve(hash), data);

                    // Also save under default folder as fallback
                    Path defaultDir = AVATARS_DIR.resolve("default");
                    Files.createDirectories(defaultDir);
                    Files.write(defaultDir.resolve("avatar"), data);
                    Files.write(defaultDir.resolve(avatarId), data);
                    Files.write(defaultDir.resolve(hash), data);

                    userEquipped.put(owner, hash);
                    saveEquippedState();

                    FiguraLink.LOGGER.info("[FiguraLimit] Successfully saved avatar for '{}' (hash: {}, size: {} bytes)", 
                            owner, hash, data.length);

                    if (!owner.equals("default")) {
                        if (FiguraLinkConfig.isClientEnvironment()) {
                            org.figuramc.figuralink.client.FiguraClientSync.onLocalAvatarSaved(owner, data, hash);
                        } else {
                            FiguraServerSync.broadcastAvatarData(owner, hash, data);
                        }
                    }

                    sendText(exchange, 200, "OK");
                    return;
                }

                // 9. Download Avatar: GET /<owner>/<avatarId>
                if (method.equals("GET") && parts.length == 3) {
                    String owner = parts[1];
                    String avatarId = parts[2];

                    Path avatarFile = AVATARS_DIR.resolve(owner).resolve(avatarId);
                    if (!Files.exists(avatarFile)) {
                        avatarFile = AVATARS_DIR.resolve(owner).resolve("avatar");
                    }
                    if (!Files.exists(avatarFile)) {
                        avatarFile = AVATARS_DIR.resolve("default").resolve(avatarId);
                    }
                    if (!Files.exists(avatarFile)) {
                        avatarFile = AVATARS_DIR.resolve("default").resolve("avatar");
                    }

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

                // 10. User Info: GET /<owner>
                if (method.equals("GET") && parts.length == 2 && !parts[1].isEmpty()) {
                    String owner = parts[1];
                    String equippedHash = userEquipped.get(owner);

                    if (equippedHash == null || equippedHash.equals("avatar") || equippedHash.length() < 32) {
                        Path userDir = AVATARS_DIR.resolve(owner);
                        if (Files.exists(userDir)) {
                            Path avatarFile = userDir.resolve("avatar");
                            if (Files.exists(avatarFile)) {
                                String hash = getSha256(avatarFile);
                                if (hash != null) {
                                    equippedHash = hash;
                                    userEquipped.put(owner, equippedHash);
                                    try {
                                        Files.copy(avatarFile, userDir.resolve(hash), StandardCopyOption.REPLACE_EXISTING);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }

                    if (equippedHash != null) {
                        String json = "{\"equipped\":[{\"owner\":\"" + owner + "\",\"hash\":\"" + equippedHash + "\",\"id\":\"avatar\"}],\"equippedBadges\":{\"pride\":[],\"special\":[]}}";
                        sendJson(exchange, 200, json);
                    } else {
                        String json = "{\"equipped\":[],\"equippedBadges\":{\"pride\":[],\"special\":[]}}";
                        sendJson(exchange, 200, json);
                    }
                    return;
                }

                // 11. Delete Avatar: DELETE /<avatarId>
                if (method.equals("DELETE") && parts.length == 2 && !parts[1].isEmpty()) {
                    String avatarId = parts[1];
                    String token = exchange.getRequestHeaders().getFirst("token");
                    String owner = (token != null && !token.trim().isEmpty()) ? token.trim() : "default";

                    Path avatarFile = AVATARS_DIR.resolve(owner).resolve(avatarId);
                    Files.deleteIfExists(avatarFile);
                    userEquipped.remove(owner);
                    saveEquippedState();

                    FiguraServerSync.broadcastAvatarUpdate(owner);
                    sendText(exchange, 200, "OK");
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
