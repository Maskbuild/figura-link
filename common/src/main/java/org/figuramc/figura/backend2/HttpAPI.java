package org.figuramc.figura.backend2;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.config.Configs;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiConsumer;

public class HttpAPI {

    private final String token;

    public HttpAPI(String token) {
        this.token = token;
    }


    // -- builders -- // 


    protected static URI getUri(String url) {
        String clean = url.startsWith("/") ? url.substring(1) : url;
        return URI.create(getBackendAddress() + "/" + clean);
    }

    protected static URI getOfficialUri(String url) {
        String clean = url.startsWith("/") ? url.substring(1) : url;
        return URI.create(BreakLimitManager.getOfficialApiBase() + "/" + clean);
    }

    protected static String getBackendAddress() {
        return BreakLimitManager.getActiveApiBase();
    }

    protected HttpRequest.Builder header(String url) {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(getUri(url))
                .header("user-agent", FiguraMod.MOD_NAME + "/" + FiguraMod.VERSION)
                .header("token", token);
        if (BreakLimitManager.isUsingCustomBackend()) {
            builder.header("x-server-address", BreakLimitManager.getActiveServerAddress());
        }
        return builder;
    }


    // -- runners -- // 


    protected static void runString(HttpRequest request, BiConsumer<Integer, String> consumer) {
        try {
            requestDebug(request);
            HttpResponse<String> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 401) NetworkStuff.reAuth();
            consumer.accept(code, response.body());
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("GOAWAY received"))
                FiguraMod.LOGGER.error("", e);
        }
    }

    protected static void runStream(HttpRequest request, BiConsumer<Integer, InputStream> consumer) {
        try {
            requestDebug(request);
            HttpResponse<InputStream> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code == 401) NetworkStuff.reAuth();
            consumer.accept(code, response.body());
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("GOAWAY received"))
                FiguraMod.LOGGER.error("", e);
        }
    }


    // -- feedback -- // 


    private static void requestDebug(HttpRequest msg) {
        if (NetworkStuff.debug)
            FiguraMod.debug( "Sent Http request:\n\t" + msg.uri().toString() + "\n\t" + msg.headers().map().toString());
    }


    // -- accessors -- // 


    // will return 200 OK if token is valid
    public HttpRequest checkAuth() {
        return header("").build();
    }

    public HttpRequest getUser(UUID id) {
        return header(id.toString()).build();
    }

    public HttpRequest getLimits() {
        return header("limits").build();
    }

    public HttpRequest getVersion() {
        return header("version").build();
    }

    public HttpRequest getMotd() {
        return header("motd").build();
    }

    public HttpRequest getAvatar(UUID owner, String id) {
        return header(owner.toString() + '/' + id).build();
    }

    public HttpRequest uploadAvatar(String id, byte[] bytes) {
        return header(id)
                .timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .header("Content-Type", "application/octet-stream")
                .build();
    }

    public HttpRequest deleteAvatar(String id) {
        return header(id).DELETE().build();
    }

    public HttpRequest setEquipped(String json) {
        return header("equip").POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json").build();
    }

    public HttpRequest updateContext(String serverAddress) {
        JsonObject body = new JsonObject();
        body.addProperty("serverAddress", serverAddress == null ? "" : serverAddress);
        return header("context")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(NetworkStuff.GSON.toJson(body), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
    }

    public HttpRequest setBadge(Integer badgeId) {
        String badge = badgeId.toString();
        return header("temp_badges").POST(HttpRequest.BodyPublishers.ofString(badge))
                .header("Content-Type", "application/json")
                .build();
    }

    public HttpRequest clearBadge() {
        return header("temp_badges").POST(HttpRequest.BodyPublishers.ofString("null"))
                .header("Content-Type", "application/json")
                .build();
    }
}
