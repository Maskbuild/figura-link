package org.figuramc.figura.backend2;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.FiguraMod;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class AuthHandler {

    private static final AtomicInteger authGeneration = new AtomicInteger(0);

    public static void auth(boolean reAuth) {
        auth(reAuth, true);
    }

    public static void auth(boolean reAuth, boolean checkBreakLimit) {
        final int myGen = authGeneration.incrementAndGet();
        NetworkStuff.async(() -> {
            if (authGeneration.get() != myGen)
                return;
            if (!reAuth && NetworkStuff.isConnected())
                return;

            if (checkBreakLimit) {
                BreakLimitManager.ensureCheckedSync(reAuth || !NetworkStuff.isConnected());
                if (authGeneration.get() != myGen)
                    return;
            }

            boolean isCustom = BreakLimitManager.isUsingCustomBackend();
            FiguraMod.LOGGER.info("Authenticating with " + FiguraMod.MOD_NAME + (isCustom ? " custom" : "") + " server...");
            NetworkStuff.backendStatus = 2;

            Minecraft minecraft = Minecraft.getInstance();
            User user = minecraft.getUser();
            try {
                String username = user.getName();
                String serverID = getServerID(username);
                if (authGeneration.get() != myGen)
                    return;
                FiguraMod.debug("Joining \"{}\" on server \"{}\"", username, serverID);
                minecraft.getMinecraftSessionService().joinServer(user.getGameProfile(), user.getAccessToken(), serverID);
                if (authGeneration.get() != myGen)
                    return;
                String token = getToken(serverID);
                if (authGeneration.get() != myGen)
                    return;
                NetworkStuff.authSuccess(token);
            // cringe exceptions
            } catch (AuthenticationUnavailableException e) {
                if (authGeneration.get() == myGen)
                    NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.serversUnavailable").getString());
            } catch (InvalidCredentialsException e) {
                if (authGeneration.get() == myGen)
                    NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.invalidSession").getString());
            } catch (InsufficientPrivilegesException e) {
                if (authGeneration.get() == myGen)
                    NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.insufficientPrivileges").getString());
            } catch (UserBannedException e) {
                if (authGeneration.get() == myGen)
                    NetworkStuff.authFail(Component.translatable("disconnect.loginFailedInfo.userBanned").getString());
            } catch (Exception e) {
                if (authGeneration.get() == myGen) {
                    NetworkStuff.authFail(e.getMessage());
                    if (isCustom) {
                        BreakLimitManager.fallbackToOfficial();
                    }
                }
            }
        });
    }

    // requests // 

    protected static String request(HttpRequest request) throws Exception {
        HttpResponse<String> response = NetworkStuff.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new Exception(response.body());
        return response.body();
    }

    private static String getServerID(String username) throws Exception {
        return request(HttpRequest.newBuilder(HttpAPI.getUri("/auth/id?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .build());
    }

    private static String getToken(String serverID) throws Exception {
        StringBuilder query = new StringBuilder("/auth/verify?id=").append(URLEncoder.encode(serverID, StandardCharsets.UTF_8));
        if (BreakLimitManager.isUsingCustomBackend()) {
            query.append("&serverAddress=").append(URLEncoder.encode(BreakLimitManager.getActiveServerAddress(), StandardCharsets.UTF_8));
        }
        return request(HttpRequest.newBuilder(HttpAPI.getUri(query.toString()))
                .timeout(Duration.ofSeconds(10))
                .build());
    }
}
