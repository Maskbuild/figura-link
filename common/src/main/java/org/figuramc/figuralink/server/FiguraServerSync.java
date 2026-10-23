package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
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
        registerServerRequestListener();
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
                        FiguraLink.LOGGER.info("[FiguraLimit] Captured MinecraftServer instance from SERVER_STARTED.");
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
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Server lifecycle listener registration skipped: " + t.getMessage());
        }
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
                                FiguraDriveBossBar.onPlayerDisconnect(player);
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

            // Receiver for /figura drive bossbar toggle packet
            Object toggleChannelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "toggle_drive");
            Object toggleHandler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null && args.length >= 2) {
                        Object player = args[1];
                        if (isPlayerOp(player)) {
                            FiguraDriveBossBar.togglePlayer(player, null);
                        } else {
                            FiguraDriveBossBar.sendPlayerMessage(player, "§cYou do not have permission to use this command.");
                        }
                    }
                    return null;
                }
            );
            regMethod.invoke(null, toggleChannelId, toggleHandler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ServerPlayNetworking receiver for 'figuralink:toggle_drive'");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Request listener registration skipped: " + t.getMessage());
        }
    }

    public static java.util.Collection<Object> getAllServerPlayers() {
        Set<Object> players = ConcurrentHashMap.newKeySet();
        if (currentServer != null) {
            try {
                Object pm = null;
                for (Method m : currentServer.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && (
                        m.getReturnType().getName().contains("PlayerManager") || 
                        m.getReturnType().getName().endsWith("class_3324"))) {
                        pm = m.invoke(currentServer);
                        break;
                    }
                }
                if (pm != null) {
                    Method getPlayerListMethod = null;
                    for (String name : new String[]{"getPlayerList", "method_14571"}) {
                        try {
                            getPlayerListMethod = pm.getClass().getMethod(name);
                            break;
                        } catch (Throwable ignored) {}
                    }
                    if (getPlayerListMethod != null) {
                        Object list = getPlayerListMethod.invoke(pm);
                        if (list instanceof java.util.Collection) {
                            for (Object item : (java.util.Collection<?>) list) {
                                if (isServerPlayer(item)) {
                                    players.add(item);
                                }
                            }
                        }
                    }
                    if (players.isEmpty()) {
                        for (java.lang.reflect.Field f : pm.getClass().getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                Object val = f.get(pm);
                                if (val instanceof java.util.Collection) {
                                    for (Object item : (java.util.Collection<?>) val) {
                                        if (isServerPlayer(item)) {
                                            players.add(item);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                FiguraLink.LOGGER.debug("[FiguraLimit] Could not query server player list: " + t.getMessage());
            }
        }
        for (Object p : onlinePlayers) {
            if (isServerPlayer(p)) {
                players.add(p);
            }
        }
        return players;
    }

    private static boolean isServerPlayer(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        return name.contains("ServerPlayerEntity") || name.endsWith("class_3222");
    }

    public static boolean isPlayerOp(Object player) {
        if (player == null) return false;
        for (String mName : new String[]{"hasPermissionLevel", "method_5687", "method_9259"}) {
            try {
                Method m = player.getClass().getMethod(mName, int.class);
                return (Boolean) m.invoke(player, 2);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static void broadcastAvatarUpdate(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.trim().isEmpty() || ownerUuid.equals("default")) {
            return;
        }
        FiguraDriveBossBar.updateBossBar();
        Runnable broadcastTask = () -> {
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

                java.util.Collection<Object> recipients = getAllServerPlayers();
                FiguraLink.LOGGER.info("[FiguraLimit] Broadcasting avatar update for '{}' to {} players...", ownerUuid, recipients.size());

                if (sendMethod != null) {
                    int sent = 0;
                    for (Object player : recipients) {
                        try {
                            Object buf = pbb.getMethod("create").invoke(null);
                            try {
                                buf.getClass().getMethod("writeString", String.class).invoke(buf, ownerUuid.trim());
                            } catch (Throwable t) {
                                buf.getClass().getMethod("method_10814", String.class).invoke(buf, ownerUuid.trim());
                            }
                            sendMethod.invoke(null, player, channelId, buf);
                            sent++;
                        } catch (Throwable t) {
                            FiguraLink.LOGGER.warn("[FiguraLimit] Could not send avatar update packet to a player: " + t.getMessage());
                        }
                    }
                    FiguraLink.LOGGER.info("[FiguraLimit] Successfully broadcasted avatar update for '{}' to {} players.", ownerUuid, sent);
                }
            } catch (Throwable t) {
                FiguraLink.LOGGER.warn("[FiguraLimit] Could not broadcast avatar update: " + t.getMessage());
            }
        };

        if (currentServer != null) {
            try {
                Method execute = currentServer.getClass().getMethod("execute", Runnable.class);
                execute.invoke(currentServer, broadcastTask);
                return;
            } catch (Throwable ignored) {}
        }
        broadcastTask.run();
    }

    private static Object resolvePlayer(Object handler) {
        if (handler == null) return null;
        try {
            return handler.getClass().getMethod("getPlayer").invoke(handler);
        } catch (Throwable ignored) {}
        try {
            return handler.getClass().getMethod("method_18784").invoke(handler);
        } catch (Throwable ignored) {}

        Class<?> curr = handler.getClass();
        while (curr != null && curr != Object.class) {
            for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(handler);
                    if (val != null) {
                        String name = val.getClass().getName();
                        if (name.contains("ServerPlayerEntity") || name.endsWith("class_3222")) {
                            return val;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    public static String getEffectiveBackendAddress(boolean isLocalhost) {
        String publicAddr = FiguraLinkConfig.getPublicBackendAddress();
        int port = FiguraLinkConfig.getBackendPort();
        if (isLocalhost) {
            return port > 0 ? ("127.0.0.1:" + port) : null;
        } else if (publicAddr != null && !publicAddr.trim().isEmpty()) {
            return publicAddr.trim();
        } else if (port > 0) {
            return "AUTO:" + port;
        } else {
            return null;
        }
    }

    public static void sendViaPacketSender(Object sender) {
        try {
            String address = getEffectiveBackendAddress(false);
            if (address == null) {
                FiguraLink.LOGGER.debug("[FiguraLimit] Backend port is not configured yet (0). Skipping packet sync.");
                return;
            }
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
        if (address == null) {
            return;
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
                    FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' to player via ServerPlayNetworking.", address);
                    return;
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not send sync packet: " + t.getMessage());
        }
    }
}
