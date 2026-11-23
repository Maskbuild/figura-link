package org.figuramc.figura.backend2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates automatic switching between the configured Figura BreakLimit
 * custom backend and the official Figura backend based on server whitelist
 * and Global Access status, while protecting player privacy.
 */
public class BreakLimitManager {

    private static final String RESOURCE_PATH = "/assets/figura_breaklimit/backend.json";
    private static final int RECHECK_INTERVAL_TICKS = 1200; // 60 seconds
    private static final Object CHECK_LOCK = new Object();
    private static final Object SERVER_LIST_LOCK = new Object();
    private static final long SERVER_LIST_TTL_MS = 5 * 60_000L; // 5 minutes
    private static final long SERVER_LIST_JOIN_REFRESH_MS = 30_000L; // 30s quick refresh when joining unrecognized server
    private static final long SERVER_LIST_FAILURE_RETRY_MS = 10_000L; // 10s backoff if never fetched or failed

    private static final String configuredBackendUrl;
    private static final String configuredWsUrl;

    private static volatile boolean usingCustomBackend = false;
    private static volatile String activeScope = "";
    private static volatile String activeServerId = "";
    private static volatile String activeServerAddress = "";
    private static volatile String completedContextKey = null;
    private static volatile long lastCheckTimeMs = 0L;

    private static volatile Set<String> registeredServerHashes = Collections.emptySet();
    private static volatile long lastServerListFetchMs = 0L;
    private static volatile long lastServerListAttemptMs = 0L;

    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private static volatile String lastObservedRawContext = null;
    private static volatile boolean initialCheckCompleted = false;
    private static int ticksUntilRecheck = 0;

    static {
        String loadedBackend = "";
        String loadedWs = "";

        try (InputStream stream = BreakLimitManager.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream != null) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("backendUrl") && !json.get("backendUrl").isJsonNull()) {
                    loadedBackend = normalizeHttpUrl(json.get("backendUrl").getAsString());
                }
                if (json.has("wsUrl") && !json.get("wsUrl").isJsonNull()) {
                    loadedWs = normalizeWsUrl(json.get("wsUrl").getAsString());
                }
            }
        } catch (Exception e) {
            FiguraMod.LOGGER.warn("Failed to read {}: {}", RESOURCE_PATH, e.getMessage());
        }

        if (!loadedBackend.isEmpty() && loadedWs.isEmpty()) {
            loadedWs = deriveWsUrl(loadedBackend);
        }

        configuredBackendUrl = loadedBackend;
        configuredWsUrl = loadedWs;

        if (!configuredBackendUrl.isEmpty()) {
            FiguraMod.LOGGER.info("Figura BreakLimit configured custom backend: {}", configuredBackendUrl);
        }
    }

    private static String normalizeHttpUrl(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) return "";
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "";
        }
        return trimmed;
    }

    private static String normalizeWsUrl(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) return "";
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
            return "";
        }
        return trimmed;
    }

    private static String deriveWsUrl(String httpUrl) {
        if (httpUrl.startsWith("https://")) {
            return "wss://" + httpUrl.substring("https://".length());
        }
        if (httpUrl.startsWith("http://")) {
            return "ws://" + httpUrl.substring("http://".length());
        }
        return "";
    }

    public static boolean isConfigured() {
        return !configuredBackendUrl.isEmpty();
    }

    public static boolean isUsingCustomBackend() {
        return usingCustomBackend && isConfigured();
    }

    public static String getActiveServerAddress() {
        return activeServerAddress;
    }

    public static String getConfiguredBackendUrl() {
        return configuredBackendUrl;
    }

    public static String getOfficialHostWithPort() {
        ServerAddress backendIP = ServerAddress.parseString(Configs.SERVER_IP.value);
        boolean hasPort = Configs.SERVER_IP.value.matches("[^:]+:\\d+");
        if (hasPort) {
            try {
                return backendIP.getHost() + ":" + backendIP.getPort();
            } catch (IllegalStateException ignored) {
            }
        }
        return backendIP.getHost();
    }

    public static String getOfficialApiBase() {
        return "https://" + getOfficialHostWithPort() + "/api";
    }

    public static String getActiveApiBase() {
        if (isUsingCustomBackend()) {
            return configuredBackendUrl + "/api";
        }
        return getOfficialApiBase();
    }

    public static String getActiveWsUrl() {
        if (isUsingCustomBackend() && !configuredWsUrl.isEmpty()) {
            return configuredWsUrl + "/ws";
        }
        return "wss://" + getOfficialHostWithPort() + "/ws";
    }

    public static String getActiveWsHost() {
        if (isUsingCustomBackend() && !configuredWsUrl.isEmpty()) {
            try {
                String host = URI.create(configuredWsUrl).getHost();
                if (host != null && !host.isEmpty()) {
                    return host;
                }
            } catch (Exception ignored) {
            }
        }
        return ServerAddress.parseString(Configs.SERVER_IP.value).getHost();
    }

    public static boolean isActiveWsTls() {
        if (isUsingCustomBackend() && !configuredWsUrl.isEmpty()) {
            return configuredWsUrl.toLowerCase(Locale.ROOT).startsWith("wss://");
        }
        return true;
    }

    public static String getCurrentServerIp() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return "";
        try {
            if (mc.getConnection() != null && mc.getCurrentServer() != null && !mc.isLocalServer()) {
                ServerData serverData = mc.getCurrentServer();
                if (serverData != null && serverData.ip != null) {
                    return serverData.ip.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Exact port of backend address normalization so SHA-256 hashes match the server directory.
     */
    private static String normalizeServerAddress(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slashIdx = cleaned.indexOf('/');
        if (slashIdx != -1) {
            cleaned = cleaned.substring(0, slashIdx);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) return "";

        if (cleaned.startsWith("[")) {
            int closeIdx = cleaned.indexOf(']');
            if (closeIdx == -1) return "";
            String host = cleaned.substring(1, closeIdx).toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return "";
            String rest = cleaned.substring(closeIdx + 1);
            String formattedHost = host.contains(":") ? "[" + host + "]" : host;
            if (rest.isEmpty()) return formattedHost;
            if (rest.startsWith(":")) {
                try {
                    int portNum = Integer.parseInt(rest.substring(1).trim());
                    if (portNum < 1 || portNum > 65535) return "";
                    return portNum == 25565 ? formattedHost : formattedHost + ":" + portNum;
                } catch (NumberFormatException e) {
                    return "";
                }
            }
            return "";
        }

        String[] parts = cleaned.split(":", -1);
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        if (parts.length == 2) {
            String host = parts[0].trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return "";
            try {
                int portNum = Integer.parseInt(parts[1].trim());
                if (portNum < 1 || portNum > 65535) return "";
                return portNum == 25565 ? host : host + ":" + portNum;
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return "";
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void refreshRegisteredServerHashes(boolean allowQuickRefresh) {
        if (!isConfigured()) {
            return;
        }
        synchronized (SERVER_LIST_LOCK) {
            long now = System.currentTimeMillis();
            boolean shouldFetch;
            if (lastServerListFetchMs == 0L) {
                shouldFetch = (now - lastServerListAttemptMs) >= SERVER_LIST_FAILURE_RETRY_MS;
            } else if ((now - lastServerListFetchMs) >= SERVER_LIST_TTL_MS) {
                shouldFetch = (now - lastServerListAttemptMs) >= SERVER_LIST_FAILURE_RETRY_MS;
            } else if (allowQuickRefresh) {
                shouldFetch = (now - lastServerListAttemptMs) >= SERVER_LIST_JOIN_REFRESH_MS;
            } else {
                shouldFetch = false;
            }

            if (!shouldFetch) {
                return;
            }
            lastServerListAttemptMs = now;

            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(configuredBackendUrl + "/api/registered-servers"))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", FiguraMod.MOD_NAME + "/" + FiguraMod.VERSION)
                        .GET()
                        .build();

                HttpResponse<String> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (json.has("hashes") && json.get("hashes").isJsonArray()) {
                        Set<String> nextHashes = new HashSet<>();
                        for (JsonElement elem : json.getAsJsonArray("hashes")) {
                            if (elem != null && elem.isJsonPrimitive()) {
                                String h = elem.getAsString().trim().toLowerCase(Locale.ROOT);
                                if (!h.isEmpty()) {
                                    nextHashes.add(h);
                                }
                            }
                        }
                        registeredServerHashes = Collections.unmodifiableSet(nextHashes);
                        lastServerListFetchMs = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                FiguraMod.LOGGER.debug("BreakLimit failed to fetch registered server hashes: {}", e.getMessage());
            }
        }
    }

    private static String resolveRegisteredOutboundAddress(String normalizedServerIp, boolean allowQuickRefresh) {
        if (normalizedServerIp == null || normalizedServerIp.isEmpty()) {
            return "";
        }
        String hash = sha256Hex(normalizedServerIp);
        if (hash.isEmpty()) {
            return "";
        }
        if (!registeredServerHashes.contains(hash)) {
            refreshRegisteredServerHashes(allowQuickRefresh);
        }
        return registeredServerHashes.contains(hash) ? normalizedServerIp : "";
    }

    public static void ensureCheckedSync(boolean force) {
        if (!isConfigured()) {
            return;
        }
        UUID uuid = FiguraMod.getLocalPlayerUUID();
        if (uuid == null) {
            return;
        }
        refreshRegisteredServerHashes(force);
        String normalizedIp = normalizeServerAddress(getCurrentServerIp());
        String outboundServerAddress = resolveRegisteredOutboundAddress(normalizedIp, force);
        lastObservedRawContext = uuid + "|" + normalizedIp;
        checkAccessInternal(uuid.toString(), outboundServerAddress, force, false);
    }

    public static void tick() {
        if (!isConfigured()) {
            return;
        }

        UUID uuid = FiguraMod.getLocalPlayerUUID();
        if (uuid == null) {
            return;
        }

        final String normalizedIp = normalizeServerAddress(getCurrentServerIp());
        final String localContextKey = uuid + "|" + normalizedIp;
        final boolean localContextChanged = !Objects.equals(localContextKey, lastObservedRawContext);

        // Stop background polling completely when using Official backend
        if (!localContextChanged) {
            if (!usingCustomBackend && initialCheckCompleted) {
                return;
            }
            if (ticksUntilRecheck > 0) {
                ticksUntilRecheck--;
                return;
            }
        }

        if (!checkInFlight.compareAndSet(false, true)) {
            return;
        }

        lastObservedRawContext = localContextKey;
        ticksUntilRecheck = RECHECK_INTERVAL_TICKS;
        final String checkUuid = uuid.toString();

        CompletableFuture.runAsync(() -> {
            try {
                String outboundServerAddress = resolveRegisteredOutboundAddress(normalizedIp, localContextChanged);
                if (initialCheckCompleted
                        && !usingCustomBackend
                        && outboundServerAddress.isEmpty()
                        && activeServerAddress.isEmpty()) {
                    return;
                }
                boolean forceCheck = !localContextChanged
                        || !Objects.equals(outboundServerAddress, activeServerAddress)
                        || !initialCheckCompleted;
                checkAccessInternal(checkUuid, outboundServerAddress, forceCheck, true);
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    private static void checkAccessInternal(String uuid, String outboundServerAddress, boolean force, boolean triggerTransition) {
        boolean switchedBackend;
        boolean switchedNonGlobalTarget;
        boolean contextOnlyChanged;
        boolean nextAllowed = false;
        String nextScope = "";
        String nextServerId = "";
        String nextServerAddress;

        synchronized (CHECK_LOCK) {
            String contextKey = uuid + "|" + outboundServerAddress;
            if (!force && Objects.equals(contextKey, completedContextKey) && (System.currentTimeMillis() - lastCheckTimeMs) < 55_000L) {
                initialCheckCompleted = true;
                return;
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("uuid", uuid);
            payload.addProperty("serverAddress", outboundServerAddress);
            String bodyJson = NetworkStuff.GSON.toJson(payload);

            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(configuredBackendUrl + "/api/check-access"))
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json")
                            .header("User-Agent", FiguraMod.MOD_NAME + "/" + FiguraMod.VERSION)
                            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<String> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() == 200) {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        nextAllowed = json.has("allowed") && json.get("allowed").getAsBoolean();
                        if (nextAllowed) {
                            if (json.has("scope") && !json.get("scope").isJsonNull()) {
                                nextScope = json.get("scope").getAsString();
                            }
                            if (json.has("serverId") && !json.get("serverId").isJsonNull()) {
                                nextServerId = json.get("serverId").getAsString();
                            }
                        }
                    }
                    break;
                } catch (Exception e) {
                    if (attempt == 0) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    FiguraMod.LOGGER.debug("BreakLimit check-access failed, keeping official fallback: {}", e.getMessage());
                    nextAllowed = false;
                    nextScope = "";
                    nextServerId = "";
                }
            }

            nextServerAddress = nextAllowed ? outboundServerAddress : "";
            switchedBackend = (nextAllowed != usingCustomBackend);
            switchedNonGlobalTarget = nextAllowed
                    && !"global".equals(nextScope)
                    && (!Objects.equals(nextScope, activeScope)
                    || !Objects.equals(nextServerId, activeServerId)
                    || !Objects.equals(nextServerAddress, activeServerAddress));
            contextOnlyChanged = nextAllowed
                    && !switchedBackend
                    && !switchedNonGlobalTarget
                    && (!Objects.equals(nextServerAddress, activeServerAddress)
                    || !Objects.equals(nextServerId, activeServerId)
                    || !Objects.equals(nextScope, activeScope));

            usingCustomBackend = nextAllowed;
            activeScope = nextAllowed ? nextScope : "";
            activeServerId = nextAllowed ? nextServerId : "";
            activeServerAddress = nextServerAddress;
            completedContextKey = contextKey;
            lastCheckTimeMs = System.currentTimeMillis();
            initialCheckCompleted = true;
            ticksUntilRecheck = RECHECK_INTERVAL_TICKS;
        }

        if (!triggerTransition) {
            return;
        }

        if (switchedBackend || switchedNonGlobalTarget) {
            FiguraMod.LOGGER.info(
                    "BreakLimit switched backend mode -> {} (scope: {}, server: {})",
                    nextAllowed ? "CUSTOM" : "OFFICIAL",
                    activeScope.isEmpty() ? "none" : activeScope,
                    nextServerAddress.isEmpty() ? "<global/none>" : nextServerAddress
            );
            NetworkStuff.reAuthWithoutRecheck();
            Minecraft.getInstance().execute(AvatarManager::reloadBackendAvatars);
        } else if (contextOnlyChanged && NetworkStuff.isConnected()) {
            FiguraMod.LOGGER.info(
                    "BreakLimit syncing Global server context seamlessly -> {}",
                    nextServerAddress.isEmpty() ? "<global/singleplayer>" : nextServerAddress
            );
            NetworkStuff.syncContext(nextServerAddress);
        }
    }

    public static void fallbackToOfficial() {
        synchronized (CHECK_LOCK) {
            if (!usingCustomBackend) {
                return;
            }
            usingCustomBackend = false;
            activeScope = "";
            activeServerId = "";
            activeServerAddress = "";
            completedContextKey = null;
            initialCheckCompleted = true;
            ticksUntilRecheck = RECHECK_INTERVAL_TICKS;
        }
        FiguraMod.LOGGER.warn("BreakLimit custom backend denied access; falling back to Official backend.");
        NetworkStuff.reAuthWithoutRecheck();
        Minecraft.getInstance().execute(AvatarManager::reloadBackendAvatars);
    }
}
