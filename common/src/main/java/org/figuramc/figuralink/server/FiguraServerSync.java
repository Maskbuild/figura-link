package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FiguraServerSync {

    public static final Set<Object> onlinePlayers = ConcurrentHashMap.newKeySet();

    public static void init() {
        if (FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerServerJoinSender();
        registerServerRequestListener();
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
                    if ("onPlayReady".equals(method.getName()) && args != null) {
                        // 1. Try PacketSender (args[1])
                        if (args.length >= 2 && args[1] != null) {
                            sendViaPacketSender(args[1]);
                        }

                        // 2. Also try ServerPlayerEntity (args[0])
                        if (args.length >= 1 && args[0] != null) {
                            Object player = resolvePlayer(args[0]);
                            if (player != null) {
                                onlinePlayers.add(player);
                                sendBackendAddressToPlayer(player);
                            }
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
            }

            // Register DISCONNECT event to remove player
            try {
                Object disconnectEvent = events.getField("DISCONNECT").get(null);
                Class<?> disconnectListener = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect");
                Object disListener = Proxy.newProxyInstance(
                    disconnectListener.getClassLoader(),
                    new Class<?>[]{disconnectListener},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 1 && args[0] != null) {
                            Object player = resolvePlayer(args[0]);
                            if (player != null) {
                                onlinePlayers.remove(player);
                            }
                        }
                        return null;
                    }
                );
                if (regMethod != null) {
                    regMethod.invoke(disconnectEvent, disListener);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Server join sender registration skipped: " + t.getMessage());
        }
    }

    private static void registerServerRequestListener() {
        try {
            Class<?> spn = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); }
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "request_backend");

            Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler");
            Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null) {
                        // args: (server, player, handler, buf, responseSender)
                        for (Object arg : args) {
                            if (arg != null && arg.getClass().getName().contains("PacketSender")) {
                                sendViaPacketSender(arg);
                                return null;
                            }
                        }
                        if (args.length >= 2 && args[1] != null) {
                            onlinePlayers.add(args[1]);
                            sendBackendAddressToPlayer(args[1]);
                        }
                    }
                    return null;
                }
            );

            Method regMethod = spn.getMethod("registerGlobalReceiver", idClass, handlerClass);
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ServerPlayNetworking receiver for 'figuralink:request_backend'");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Request listener registration skipped: " + t.getMessage());
        }
    }

    public static void broadcastAvatarUpdate(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.trim().isEmpty() || ownerUuid.equals("default")) {
            return;
        }
        try {
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); }
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }
            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "avatar_update");

            Class<?> pbb = Class.forName("net.fabricmc.fabric.api.networking.v1.PacketByteBufs");
            Class<?> spn = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Method sendMethod = null;
            for (Method m : spn.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 3) {
                    sendMethod = m;
                    break;
                }
            }

            if (sendMethod != null) {
                int sent = 0;
                for (Object player : onlinePlayers) {
                    try {
                        Object buf = pbb.getMethod("create").invoke(null);
                        try {
                            buf.getClass().getMethod("writeString", String.class).invoke(buf, ownerUuid.trim());
                        } catch (Throwable t) {
                            buf.getClass().getMethod("method_10814", String.class).invoke(buf, ownerUuid.trim());
                        }
                        sendMethod.invoke(null, player, channelId, buf);
                        sent++;
                    } catch (Throwable ignored) {}
                }
                FiguraLink.LOGGER.info("[FiguraLimit] Broadcasted avatar update for '{}' to {} players.", ownerUuid, sent);
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not broadcast avatar update: " + t.getMessage());
        }
    }

    private static Object resolvePlayer(Object handler) {
        try {
            return handler.getClass().getMethod("getPlayer").invoke(handler);
        } catch (Throwable ignored) {}

        for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
            String typeName = f.getType().getName();
            String fieldName = f.getName();
            if (typeName.contains("ServerPlayerEntity") || typeName.endsWith("class_3222") ||
                fieldName.equals("player") || fieldName.equals("field_14161")) {
                try {
                    f.setAccessible(true);
                    Object p = f.get(handler);
                    if (p != null) return p;
                } catch (Throwable ignored) {}
            }
        }

        if (handler.getClass().getSuperclass() != null) {
            for (java.lang.reflect.Field f : handler.getClass().getSuperclass().getDeclaredFields()) {
                String typeName = f.getType().getName();
                String fieldName = f.getName();
                if (typeName.contains("ServerPlayerEntity") || typeName.endsWith("class_3222") ||
                    fieldName.equals("player") || fieldName.equals("field_14161")) {
                    try {
                        f.setAccessible(true);
                        Object p = f.get(handler);
                        if (p != null) return p;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    public static String getEffectiveBackendAddress(boolean isLocalhost) {
        String publicAddr = FiguraLinkConfig.getPublicBackendAddress();
        if (isLocalhost) {
            return "127.0.0.1:" + FiguraLinkConfig.getBackendPort();
        } else if (publicAddr != null && !publicAddr.trim().isEmpty()) {
            return publicAddr.trim();
        } else {
            return "AUTO:" + FiguraLinkConfig.getBackendPort();
        }
    }

    public static void sendViaPacketSender(Object sender) {
        try {
            String address = getEffectiveBackendAddress(false);
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

            for (Method m : sender.getClass().getMethods()) {
                if ("sendPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.invoke(sender, channelId, buf);
                    FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' via PacketSender.", address);
                    return;
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Failed to send via PacketSender: {}", t.getMessage());
        }
    }

    public static void sendBackendAddressToPlayer(Object serverPlayer) {
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

        String address = getEffectiveBackendAddress(isLocalhost);

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
                    FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' to player via ServerPlayNetworking.", address);
                    return;
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not send sync packet: " + t.getMessage());
        }
    }
}
