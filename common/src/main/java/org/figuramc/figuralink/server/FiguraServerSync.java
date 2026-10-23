package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.util.FiguraPacketUtil;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FiguraServerSync {

    public static final Set<Object> onlinePlayers = ConcurrentHashMap.newKeySet();
    public static volatile Object currentServer = null;

    public static void init() {
        if (FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerServerLifecycleListener();
        registerServerJoinSender();
        startPresenceReporter();
    }

    private static void registerServerLifecycleListener() {
        try {
            Class<?> sle = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            Object startedEvent = sle.getField("SERVER_STARTED").get(null);
            Class<?> startedListener = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted");
            Object listener = Proxy.newProxyInstance(
                startedListener.getClassLoader(),
                new Class<?>[]{startedListener},
                (proxy, method, args) -> {
                    if (args != null && args.length >= 1 && args[0] != null) {
                        currentServer = args[0];
                        FiguraLink.LOGGER.info("[FiguraLimit] Captured MinecraftServer instance.");
                    }
                    return null;
                }
            );
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            for (Method m : eventClass.getMethods()) {
                if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(startedEvent, listener);
                    break;
                }
            }
        } catch (Throwable ignored) {}
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
                        if (args.length >= 3 && args[2] != null) {
                            currentServer = args[2];
                        }

                        final Object sender = (args.length >= 2) ? args[1] : null;
                        final Object player = (args.length >= 1) ? resolvePlayer(args[0]) : null;

                        if (player != null) {
                            onlinePlayers.add(player);
                        }

                        // Send backend sync packet to player
                        new Thread(() -> {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            if (sender != null) {
                                sendViaPacketSender(sender);
                            } else if (player != null) {
                                sendBackendAddressToPlayer(player);
                            }
                        }, "FiguraLimit-JoinSync").start();
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

            // Register DISCONNECT event
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
            FiguraLink.LOGGER.warn("[FiguraLimit] Server join sender registration: " + t.getMessage());
        }
    }

    private static Object resolvePlayer(Object handler) {
        if (handler == null) return null;
        if (isServerPlayer(handler)) return handler;

        for (String mName : new String[]{"getPlayer", "method_3353", "method_14384", "method_14392"}) {
            try {
                Method m = handler.getClass().getMethod(mName);
                Object p = m.invoke(handler);
                if (isServerPlayer(p)) return p;
            } catch (Throwable ignored) {}
        }
        for (String fName : new String[]{"player", "field_14140"}) {
            try {
                Field f = handler.getClass().getDeclaredField(fName);
                f.setAccessible(true);
                Object p = f.get(handler);
                if (isServerPlayer(p)) return p;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isServerPlayer(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        return name.contains("ServerPlayer") || name.endsWith("class_3222");
    }

    public static String getEffectiveBackendAddress() {
        String addr = FiguraLinkConfig.getBackendAddress();
        if (addr != null && !addr.trim().isEmpty()) {
            return addr.trim();
        }
        return "";
    }

    public static void sendViaPacketSender(Object sender) {
        if (sender == null) return;
        String address = getEffectiveBackendAddress();
        if (address.isEmpty()) return;

        try {
            Object channelId = FiguraPacketUtil.createIdentifier("figuralink", "sync_backend");
            Object buf = FiguraPacketUtil.createBuf();
            FiguraPacketUtil.writeString(buf, address);

            for (Method m : sender.getClass().getMethods()) {
                if ("createPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                    Object packet = m.invoke(sender, channelId, buf);
                    if (packet != null) {
                        for (Method sm : sender.getClass().getMethods()) {
                            if ("sendPacket".equals(sm.getName()) && sm.getParameterCount() == 1) {
                                sm.invoke(sender, packet);
                                FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' via PacketSender.", address);
                                return;
                            }
                        }
                    }
                }
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
        if (serverPlayer == null) return;
        String address = getEffectiveBackendAddress();
        if (address.isEmpty()) return;

        Object buf = FiguraPacketUtil.createBuf();
        FiguraPacketUtil.writeString(buf, address);
        FiguraPacketUtil.sendS2C(serverPlayer, "sync_backend", buf);
        FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' to player.", address);
    }

    public static boolean isPlayerOp(Object player) {
        if (player == null) return false;
        for (String mName : new String[]{"hasPermissionLevel", "method_5687"}) {
            try {
                Method m = player.getClass().getMethod(mName, int.class);
                return (Boolean) m.invoke(player, 2);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void startPresenceReporter() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15000);
                    reportPresence();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "FiguraLimit-PresenceReporter");
        thread.setDaemon(true);
        thread.start();
    }

    private static void reportPresence() {
        String backendUrl = FiguraLinkConfig.getBackendUrl();
        String secret = FiguraLinkConfig.getServerSecret();
        if (backendUrl == null || backendUrl.trim().isEmpty() || secret == null || secret.trim().isEmpty()) {
            return;
        }

        try {
            String urlStr = backendUrl.trim();
            if (urlStr.endsWith("/")) urlStr = urlStr.substring(0, urlStr.length() - 1);
            urlStr += "/api/server/presence";

            StringBuilder json = new StringBuilder("{\"players\":[");
            boolean first = true;
            for (Object player : onlinePlayers) {
                UUID uuid = getPlayerUuid(player);
                String name = getPlayerName(player);
                if (uuid != null && name != null) {
                    if (!first) json.append(",");
                    json.append("{\"uuid\":\"").append(uuid).append("\",\"name\":\"").append(name).append("\",\"hasFiguraLink\":true}");
                    first = false;
                }
            }
            json.append("]}");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-server-secret", secret.trim());
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable ignored) {}
    }

    public static UUID getPlayerUuid(Object serverPlayer) {
        if (serverPlayer == null) return null;
        for (String mName : new String[]{"getUuid", "method_5667", "getGameProfile"}) {
            try {
                Method m = serverPlayer.getClass().getMethod(mName);
                Object res = m.invoke(serverPlayer);
                if (res instanceof UUID) return (UUID) res;
                if (res != null) {
                    Method getId = res.getClass().getMethod("getId");
                    Object id = getId.invoke(res);
                    if (id instanceof UUID) return (UUID) id;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static String getPlayerName(Object serverPlayer) {
        if (serverPlayer == null) return null;
        for (String mName : new String[]{"getName", "getEntityName", "method_5477", "method_5820"}) {
            try {
                Method m = serverPlayer.getClass().getMethod(mName);
                Object res = m.invoke(serverPlayer);
                if (res instanceof String) return (String) res;
                if (res != null) {
                    Method getString = res.getClass().getMethod("getString");
                    return (String) getString.invoke(res);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
