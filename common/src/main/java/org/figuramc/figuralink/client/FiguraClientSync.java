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

        registerClientReceiver();
        registerClientAutoSwitch();
    }

    private static void registerClientReceiver() {
        try {
            Class<?> cpn = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); } 
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "sync_backend");

            Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler");
            Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null && args.length >= 3) {
                        Object buf = args[2];
                        String address = null;
                        try {
                            address = (String) buf.getClass().getMethod("readString").invoke(buf);
                        } catch (Throwable t) {
                            try {
                                address = (String) buf.getClass().getMethod("method_10804").invoke(buf);
                            } catch (Throwable ignored) {}
                        }

                        if (address != null && !address.trim().isEmpty()) {
                            FiguraLink.LOGGER.info("[FiguraLimit] Received server backend address from network packet: {}", address);
                            setFiguraServerIp(address.trim());
                        }
                    }
                    return null;
                }
            );

            Method regMethod = cpn.getMethod("registerGlobalReceiver", idClass, handlerClass);
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayNetworking receiver for 'figuralink:sync_backend'");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Client networking receiver skipped: " + t.getMessage());
        }
    }

    private static void registerClientAutoSwitch() {
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
