package org.figuramc.figura.backend2;

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
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates automatic switching between the configured Figura BreakLimit
 * custom backend and the official Figura backend based on server whitelist
 * and Global Access status.
 */
public class BreakLimitManager {

    private static final String RESOURCE_PATH = "/assets/figura_breaklimit/backend.json";
    private static final int RECHECK_INTERVAL_TICKS = 1200; // 60 seconds
    private static final Object CHECK_LOCK = new Object();

    private static final String configuredBackendUrl;
    private static final String configuredWsUrl;

    private static volatile boolean usingCustomBackend = false;
    private static volatile String activeScope = "";
    private static volatile String activeServerId = "";
    private static volatile String activeServerAddress = "";
    private static volatile String completedContextKey = null;
    private static volatile long lastCheckTimeMs = 0L;

    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private static volatile String lastRequestedContext = null;
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

    public static void ensureCheckedSync(boolean force) {
        if (!isConfigured()) {
            return;
        }
        UUID uuid = FiguraMod.getLocalPlayerUUID();
        if (uuid == null) {
            return;
        }
        checkAccessInternal(uuid.toString(), getCurrentServerIp(), force, false);
    }

    public static void tick() {
        if (!isConfigured()) {
            return;
        }

        UUID uuid = FiguraMod.getLocalPlayerUUID();
        if (uuid == null) {
            return;
        }

        String currentServerIp = getCurrentServerIp();
        String contextKey = uuid + "|" + currentServerIp;
        boolean contextChanged = !Objects.equals(contextKey, lastRequestedContext);

        if (!contextChanged) {
            if (ticksUntilRecheck > 0) {
                ticksUntilRecheck--;
                return;
            }
        }

        if (!checkInFlight.compareAndSet(false, true)) {
            return;
        }

        lastRequestedContext = contextKey;
        ticksUntilRecheck = RECHECK_INTERVAL_TICKS;
        final String checkUuid = uuid.toString();
        final String checkServerIp = currentServerIp;

        CompletableFuture.runAsync(() -> {
            try {
                checkAccessInternal(checkUuid, checkServerIp, true, true);
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    private static void checkAccessInternal(String uuid, String serverAddress, boolean force, boolean triggerTransition) {
        boolean switchedBackend;
        boolean switchedNonGlobalTarget;
        boolean contextOnlyChanged;
        boolean nextAllowed = false;
        String nextScope = "";
        String nextServerId = "";
        String nextServerAddress;

        synchronized (CHECK_LOCK) {
            String contextKey = uuid + "|" + serverAddress;
            if (!force && Objects.equals(contextKey, completedContextKey) && (System.currentTimeMillis() - lastCheckTimeMs) < 55_000L) {
                return;
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("uuid", uuid);
            payload.addProperty("serverAddress", serverAddress);
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

            nextServerAddress = nextAllowed ? serverAddress : "";
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
            lastRequestedContext = contextKey;
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
            ticksUntilRecheck = RECHECK_INTERVAL_TICKS;
        }
        FiguraMod.LOGGER.warn("BreakLimit custom backend denied access; falling back to Official backend.");
        NetworkStuff.reAuthWithoutRecheck();
        Minecraft.getInstance().execute(AvatarManager::reloadBackendAvatars);
    }
}
