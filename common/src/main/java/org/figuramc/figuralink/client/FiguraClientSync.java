package org.figuramc.figuralink.client;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.util.FiguraPacketUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FiguraClientSync {

    public static final String OFFICIAL_FIGURA_IP = "figura.moonlight-devs.org";

    public static void init() {
        if (!FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerClientReceiver();
        registerClientConnectionEvents();
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
                    if ("receive".equals(method.getName()) && args != null) {
                        Object buf = null;
                        for (Object arg : args) {
                            if (arg != null) {
                                String cName = arg.getClass().getName();
                                if (cName.contains("Buf") || cName.endsWith("class_2540")) {
                                    buf = arg;
                                    break;
                                }
                            }
                        }
                        if (buf == null && args.length >= 3) {
                            buf = args[2];
                        }
                        if (buf != null) {
                            String address = FiguraPacketUtil.readString(buf);
                            if (address != null && !address.trim().isEmpty()) {
                                FiguraLink.LOGGER.info("[FiguraLimit] Received server backend sync packet: {}", address.trim());
                                setFiguraServerIp(address.trim());
                            }
                        }
                    }
                    return null;
                }
            );

            Method regMethod = cpn.getMethod("registerGlobalReceiver", idClass, handlerClass);
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered Client receiver for 'figuralink:sync_backend'");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Client networking registration skipped: " + t.getMessage());
        }
    }

    private static void registerClientConnectionEvents() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Method regMethod = null;
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            for (Method m : eventClass.getMethods()) {
                if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                    regMethod = m;
                    break;
                }
            }

            // Register DISCONNECT event: Restore official cloud when leaving server
            try {
                Object disconnectEvent = eventsClass.getField("DISCONNECT").get(null);
                Class<?> disconnectClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect");
                Object disconnectListener = Proxy.newProxyInstance(
                    disconnectClass.getClassLoader(),
                    new Class<?>[]{disconnectClass},
                    (proxy, method, args) -> {
                        FiguraLink.LOGGER.info("[FiguraLimit] Client disconnected from server: restoring official cloud.");
                        setFiguraServerIp(OFFICIAL_FIGURA_IP);
                        return null;
                    }
                );
                if (regMethod != null) {
                    regMethod.invoke(disconnectEvent, disconnectListener);
                    FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayConnectionEvents.DISCONNECT.");
                }
            } catch (Throwable t) {
                FiguraLink.LOGGER.debug("[FiguraLimit] Client disconnect listener registration: " + t.getMessage());
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Client connection listener skipped: " + t.getMessage());
        }
    }

    public static String getRemoteServerHost() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.MinecraftClient");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object serverEntry = mcClass.getMethod("getCurrentServerEntry").invoke(mc);
            if (serverEntry != null) {
                String address = (String) serverEntry.getClass().getField("address").get(serverEntry);
                if (address != null && !address.isEmpty()) {
                    return address.split(":")[0];
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> mcClass = Class.forName("net.minecraft.class_310");
            Object mc = mcClass.getMethod("method_1551").invoke(null);
            Object serverEntry = mcClass.getMethod("method_1558").invoke(mc);
            if (serverEntry != null) {
                String address = (String) serverEntry.getClass().getField("field_3761").get(serverEntry);
                if (address != null && !address.isEmpty()) {
                    return address.split(":")[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void switchToServerBackend() {
        String host = getRemoteServerHost();
        if (host != null && !host.isEmpty()) {
            setFiguraServerIp(host + ":8080");
        }
    }

    public static boolean setFiguraServerIp(String target) {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            Method setValue = serverIpConfig.getClass().getMethod("setValue", String.class);
            setValue.invoke(serverIpConfig, target);

            FiguraLink.LOGGER.info("[FiguraLimit] Automatically updated Figura Cloud IP to: {}", target);

            // Trigger re-authentication with backend
            try {
                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                ns.getMethod("reAuth").invoke(null);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Failed to update Figura Cloud IP to " + target + ": " + t.getMessage());
            return false;
        }
    }
}
