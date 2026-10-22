package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FiguraServerSync {

    public static void init() {
        if (FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerServerJoinSender();
    }

    private static void registerServerJoinSender() {
        try {
            Class<?> events = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
            Object joinEvent = events.getField("JOIN").get(null);

            Class<?> joinListener = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join");
            Object listener = Proxy.newProxyInstance(
                joinListener.getClassLoader(),
                new Class<?>[]{joinListener},
                (proxy, method, args) -> {
                    if ("onPlayReady".equals(method.getName()) && args != null && args.length >= 1) {
                        Object handler = args[0];
                        Object player = null;
                        try {
                            player = handler.getClass().getMethod("getPlayer").invoke(handler);
                        } catch (Throwable ignored) {}

                        if (player == null) {
                            for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
                                String typeName = f.getType().getName();
                                String fieldName = f.getName();
                                if (typeName.contains("ServerPlayerEntity") || typeName.endsWith("class_3222") ||
                                    fieldName.equals("player") || fieldName.equals("field_14161")) {
                                    try {
                                        f.setAccessible(true);
                                        player = f.get(handler);
                                        if (player != null) break;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }

                        if (player == null && handler.getClass().getSuperclass() != null) {
                            for (java.lang.reflect.Field f : handler.getClass().getSuperclass().getDeclaredFields()) {
                                String typeName = f.getType().getName();
                                String fieldName = f.getName();
                                if (typeName.contains("ServerPlayerEntity") || typeName.endsWith("class_3222") ||
                                    fieldName.equals("player") || fieldName.equals("field_14161")) {
                                    try {
                                        f.setAccessible(true);
                                        player = f.get(handler);
                                        if (player != null) break;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }

                        if (player != null) {
                            sendBackendAddressToPlayer(player);
                        } else {
                            FiguraLink.LOGGER.warn("[FiguraLimit] Could not resolve ServerPlayerEntity from handler {}", handler.getClass().getName());
                        }
                    }
                    return null;
                }
            );

            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            Method regMethod = null;
            for (Method m : eventClass.getMethods()) {
                if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                    regMethod = m;
                    break;
                }
            }
            if (regMethod != null) {
                regMethod.setAccessible(true);
                regMethod.invoke(joinEvent, listener);
                FiguraLink.LOGGER.info("[FiguraLimit] Registered ServerPlayConnectionEvents.JOIN for backend auto-sync.");
            } else {
                FiguraLink.LOGGER.warn("[FiguraLimit] Could not find register method on ServerPlayConnectionEvents.JOIN");
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Server join sender registration skipped: " + t.getMessage());
        }
    }

    public static void sendBackendAddressToPlayer(Object serverPlayer) {
        String publicAddr = FiguraLinkConfig.getPublicBackendAddress();
        boolean isLocalhost = false;
        try {
            for (java.lang.reflect.Field f : serverPlayer.getClass().getFields()) {
                if (f.getName().equals("networkHandler") || f.getName().equals("field_13987")) {
                    Object nh = f.get(serverPlayer);
                    if (nh != null) {
                        for (java.lang.reflect.Field cf : nh.getClass().getFields()) {
                            if (cf.getName().equals("connection") || cf.getName().equals("field_14384")) {
                                Object conn = cf.get(nh);
                                if (conn != null) {
                                    Method m = conn.getClass().getMethod("getAddress");
                                    Object addr = m.invoke(conn);
                                    if (addr != null && addr.toString().contains("127.0.0.1")) {
                                        isLocalhost = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        String address;
        if (isLocalhost) {
            address = "127.0.0.1:" + FiguraLinkConfig.getBackendPort();
        } else if (publicAddr != null && !publicAddr.trim().isEmpty()) {
            address = publicAddr.trim();
        } else {
            address = "AUTO:" + FiguraLinkConfig.getBackendPort();
            FiguraLink.LOGGER.info("[FiguraLimit] Remote player joined: using automatic server host resolution with backend port {}", FiguraLinkConfig.getBackendPort());
        }

        try {
            Class<?> spn = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); }
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "sync_backend");

            Class<?> pbb = Class.forName("net.fabricmc.fabric.api.networking.v1.PacketByteBufs");
            Object buf = pbb.getMethod("create").invoke(null);
            try {
                buf.getClass().getMethod("writeString", String.class).invoke(buf, address);
            } catch (Throwable t) {
                buf.getClass().getMethod("method_10814", String.class).invoke(buf, address);
            }

            for (Method m : spn.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 3) {
                    m.invoke(null, serverPlayer, channelId, buf);
                    FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' to player via network packet.", address);
                    return;
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not send sync packet: " + t.getMessage());
        }
    }
}
