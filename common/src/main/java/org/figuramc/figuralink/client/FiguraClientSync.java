package org.figuramc.figuralink.client;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FiguraClientSync {

    public static void init() {
        if (!FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Object joinEvent = eventsClass.getField("JOIN").get(null);
            Method register = joinEvent.getClass().getMethod("register", Object.class);

            Class<?> listenerClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Join");
            Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPlayReady".equals(method.getName())) {
                        switchToServerBackend();
                    }
                    return null;
                }
            );
            register.invoke(joinEvent, listener);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayConnectionEvents.JOIN for automatic backend switching.");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Client connection listener skipped: " + t.getMessage());
        }
    }

    public static void switchToServerBackend() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.MinecraftClient");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object serverEntry = mcClass.getMethod("getCurrentServerEntry").invoke(mc);
            if (serverEntry != null) {
                String address = (String) serverEntry.getClass().getField("address").get(serverEntry);
                if (address != null && !address.isEmpty()) {
                    String host = address.split(":")[0];
                    int port = FiguraLinkConfig.getBackendPort();
                    setFiguraServerIp(host + ":" + port);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // Fallback to intermediary class_310
        try {
            Class<?> mcClass = Class.forName("net.minecraft.class_310");
            Object mc = mcClass.getMethod("method_1551").invoke(null);
            Object serverEntry = mcClass.getMethod("method_1558").invoke(mc);
            if (serverEntry != null) {
                String address = (String) serverEntry.getClass().getField("field_3761").get(serverEntry);
                if (address != null && !address.isEmpty()) {
                    String host = address.split(":")[0];
                    int port = FiguraLinkConfig.getBackendPort();
                    setFiguraServerIp(host + ":" + port);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean setFiguraServerIp(String target) {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            Method setValue = serverIpConfig.getClass().getMethod("setValue", String.class);
            setValue.invoke(serverIpConfig, target);

            FiguraLink.LOGGER.info("[FiguraLimit] Automatically updated Figura Cloud IP to: {}", target);

            // Trigger re-authentication with new backend
            Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
            ns.getMethod("reAuth").invoke(null);
            return true;
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Failed to update Figura Cloud IP to " + target + ": " + t.getMessage());
            return false;
        }
    }
}
