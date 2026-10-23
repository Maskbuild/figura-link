package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.util.AvatarTransferTracker;
import org.figuramc.figuralink.util.FiguraPacketUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FiguraServerSync {

    public static final Set<Object> onlinePlayers = ConcurrentHashMap.newKeySet();
    public static volatile Object currentServer = null;
    private static final AvatarTransferTracker serverTracker = new AvatarTransferTracker();

    public static void init() {
        if (FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerServerLifecycleListener();
        registerServerJoinSender();
        registerServerReceivers();
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

                        final Object sender = (args.length >= 2) ? args[1] : null;
                        final Object player = (args.length >= 1) ? resolvePlayer(args[0]) : null;

                        if (player != null) {
                            onlinePlayers.add(player);
                        }

                        // Schedule sync across multiple delays to guarantee client receipt
                        new Thread(() -> {
                            int[] delays = new int[]{0, 200, 600, 1500};
                            for (int delay : delays) {
                                if (delay > 0) {
                                    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                                }
                                if (sender != null) {
                                    sendViaPacketSender(sender);
                                }
                                if (player != null) {
                                    sendBackendAddressToPlayer(player);
                                }
                            }
                            if (player != null) {
                                syncAllAvatarsToPlayer(player);
                            }
                        }, "FiguraLimit-JoinSyncSchedule").start();
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

    private interface ServerPacketReceiver {
        void onPacket(Object player, Object buf, Object sender);
    }

    private static void registerServerReceivers() {
        try {
            Class<?> spn = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); }
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler");
            Method regMethod = spn.getMethod("registerGlobalReceiver", idClass, handlerClass);

            // 1. request_backend
            registerOneReceiver(spn, idClass, handlerClass, regMethod, "request_backend", (player, buf, sender) -> {
                if (sender != null) {
                    sendViaPacketSender(sender);
                }
                if (player != null) {
                    onlinePlayers.add(player);
                    sendBackendAddressToPlayer(player);
                }
            });

            // 2. toggle_drive (/figura drive BossBar)
            registerOneReceiver(spn, idClass, handlerClass, regMethod, "toggle_drive", (player, buf, sender) -> {
                if (player == null) return;
                Runnable task = () -> {
                    if (isPlayerOp(player)) {
                        FiguraDriveBossBar.togglePlayer(player, null);
                    } else {
                        FiguraDriveBossBar.sendPlayerMessage(player, "§cYou do not have permission to use this command.");
                    }
                };
                if (currentServer != null) {
                    try {
                        Method exec = currentServer.getClass().getMethod("execute", Runnable.class);
                        exec.invoke(currentServer, task);
                        return;
                    } catch (Throwable ignored) {}
                }
                task.run();
            });

            // 3. handshake (C2S on join: [ownerUuid] [equippedHash])
            registerOneReceiver(spn, idClass, handlerClass, regMethod, "handshake", (player, buf, sender) -> {
                String clientUuid = FiguraPacketUtil.readString(buf);
                String clientHash = FiguraPacketUtil.readString(buf);
                if (clientUuid != null && !clientUuid.trim().isEmpty() && clientHash != null && !clientHash.trim().isEmpty()) {
                    clientUuid = clientUuid.trim();
                    clientHash = clientHash.trim();
                    byte[] existing = EmbeddedFiguraServer.getAvatarBytes(clientUuid);
                    String serverHash = EmbeddedFiguraServer.getUserEquipped(clientUuid);

                    if (existing == null || serverHash == null || !serverHash.equalsIgnoreCase(clientHash)) {
                        FiguraLink.LOGGER.info("[FiguraLimit] Server requesting missing avatar for {} (client hash: {})", clientUuid, clientHash);
                        Object reqBuf = FiguraPacketUtil.createBuf();
                        FiguraPacketUtil.writeString(reqBuf, clientUuid);
                        FiguraPacketUtil.sendS2C(player, "request_avatar", reqBuf);
                    } else {
                        FiguraLink.LOGGER.info("[FiguraLimit] Server already has current avatar for {}", clientUuid);
                        broadcastAvatarData(clientUuid, clientHash, existing, player);
                    }
                }
            });

            // 4. avatar_chunk (C2S upload chunk)
            registerOneReceiver(spn, idClass, handlerClass, regMethod, "avatar_chunk", (player, buf, sender) -> {
                String ownerUuid = FiguraPacketUtil.readString(buf);
                String hash = FiguraPacketUtil.readString(buf);
                int chunkIndex = FiguraPacketUtil.readInt(buf);
                int totalChunks = FiguraPacketUtil.readInt(buf);
                byte[] chunkData = FiguraPacketUtil.readByteArray(buf);

                byte[] fullAvatar = serverTracker.handleIncomingChunk(ownerUuid, hash, chunkIndex, totalChunks, chunkData);
                if (fullAvatar != null) {
                    if (fullAvatar.length > FiguraLinkConfig.getMaxAvatarSize()) {
                        FiguraLink.LOGGER.warn("[FiguraLimit] Avatar from {} exceeds size limit ({} bytes)", ownerUuid, fullAvatar.length);
                        return;
                    }
                    if (FiguraLinkConfig.isTotalStorageExceeded(EmbeddedFiguraServer.getAvatarsDir())) {
                        FiguraLink.LOGGER.warn("[FiguraLimit] Server storage limit exceeded, rejecting avatar for {}", ownerUuid);
                        return;
                    }

                    EmbeddedFiguraServer.saveAvatarFile(ownerUuid, hash, fullAvatar);
                    FiguraDriveBossBar.updateBossBar();

                    // Broadcast to other players
                    broadcastAvatarData(ownerUuid, hash, fullAvatar, player);
                }
            });

            // 5. request_avatar (C2S client pulls missing avatar)
            registerOneReceiver(spn, idClass, handlerClass, regMethod, "request_avatar", (player, buf, sender) -> {
                String requestedUuid = FiguraPacketUtil.readString(buf);
                if (requestedUuid != null && !requestedUuid.trim().isEmpty()) {
                    requestedUuid = requestedUuid.trim();
                    byte[] data = EmbeddedFiguraServer.getAvatarBytes(requestedUuid);
                    if (data != null) {
                        String hash = EmbeddedFiguraServer.getUserEquipped(requestedUuid);
                        sendAvatarToPlayer(player, requestedUuid, hash, data);
                    }
                }
            });

        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Server receiver registration skipped: " + t.getMessage());
        }
    }

    private static void registerOneReceiver(Class<?> spn, Class<?> idClass, Class<?> handlerClass, Method regMethod, String channelName, ServerPacketReceiver receiver) {
        try {
            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", channelName);
            Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null) {
                        Object player = (args.length >= 2) ? args[1] : null;
                        Object buf = null;
                        Object sender = null;
                        for (Object arg : args) {
                            if (arg != null) {
                                String cName = arg.getClass().getName();
                                if (cName.contains("Buf") || cName.endsWith("class_2540")) {
                                    buf = arg;
                                } else if (cName.contains("PacketSender")) {
                                    sender = arg;
                                }
                            }
                        }
                        if (buf == null && args.length >= 4) {
                            buf = args[3];
                        }
                        if (receiver != null && buf != null) {
                            receiver.onPacket(player, buf, sender);
                        }
                    }
                    return null;
                }
            );
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered Server receiver for 'figuralink:{}'", channelName);
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Failed to register receiver for '{}': {}", channelName, t.getMessage());
        }
    }

    public static void broadcastAvatarData(String ownerUuid, String hash, byte[] data) {
        broadcastAvatarData(ownerUuid, hash, data, null);
    }

    public static void broadcastAvatarData(String ownerUuid, String hash, byte[] data, Object excludePlayer) {
        if (ownerUuid == null || data == null || data.length == 0) return;
        new Thread(() -> {
            Collection<Object> recipients = getAllServerPlayers();
            FiguraLink.LOGGER.info("[FiguraLimit] Broadcasting avatar data for {} to {} players...", ownerUuid, recipients.size());
            for (Object player : recipients) {
                if (excludePlayer != null && player.equals(excludePlayer)) continue;
                sendAvatarToPlayer(player, ownerUuid, hash, data);
            }
            broadcastAvatarUpdate(ownerUuid);
        }, "FiguraLimit-ServerBroadcast").start();
    }

    public static void sendAvatarToPlayer(Object player, String ownerUuid, String hash, byte[] data) {
        if (player == null || ownerUuid == null || data == null) return;
        AvatarTransferTracker.streamChunks(ownerUuid, hash, data, (uuid, h, chunkIdx, totalChunks, chunk) -> {
            Object buf = FiguraPacketUtil.createBuf();
            FiguraPacketUtil.writeString(buf, uuid);
            FiguraPacketUtil.writeString(buf, h);
            FiguraPacketUtil.writeInt(buf, chunkIdx);
            FiguraPacketUtil.writeInt(buf, totalChunks);
            FiguraPacketUtil.writeByteArray(buf, chunk);
            FiguraPacketUtil.sendS2C(player, "avatar_chunk", buf);
        });
    }

    public static void syncAllAvatarsToPlayer(Object player) {
        if (player == null) return;
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            Map<String, String> equipped = EmbeddedFiguraServer.getUserEquippedMap();
            FiguraLink.LOGGER.info("[FiguraLimit] Syncing {} server avatars to new player...", equipped.size());
            for (Map.Entry<String, String> entry : equipped.entrySet()) {
                String owner = entry.getKey();
                String hash = entry.getValue();
                byte[] data = EmbeddedFiguraServer.getAvatarBytes(owner);
                if (data != null && data.length > 0) {
                    sendAvatarToPlayer(player, owner, hash, data);
                }
            }
        }, "FiguraLimit-PlayerInitialSync").start();
    }

    public static Collection<Object> getAllServerPlayers() {
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
                        if (list instanceof Collection) {
                            for (Object item : (Collection<?>) list) {
                                if (isServerPlayer(item)) {
                                    players.add(item);
                                }
                            }
                        }
                    }
                    if (players.isEmpty()) {
                        for (Field f : pm.getClass().getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                Object val = f.get(pm);
                                if (val instanceof Collection) {
                                    for (Object item : (Collection<?>) val) {
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
                boolean res = (Boolean) m.invoke(player, 2);
                if (res) return true;
            } catch (Throwable ignored) {}
        }

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
                    Object profile = null;
                    for (String mName : new String[]{"getGameProfile", "method_5678"}) {
                        try {
                            profile = player.getClass().getMethod(mName).invoke(player);
                            if (profile != null) break;
                        } catch (Throwable ignored) {}
                    }
                    if (profile != null) {
                        for (String mName : new String[]{"isOperator", "method_14596"}) {
                            try {
                                Method isOpMethod = pm.getClass().getMethod(mName, profile.getClass());
                                boolean res = (Boolean) isOpMethod.invoke(pm, profile);
                                if (res) return true;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
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
                Collection<Object> recipients = getAllServerPlayers();
                FiguraLink.LOGGER.info("[FiguraLimit] Broadcasting avatar_update packet for '{}' to {} players...", ownerUuid, recipients.size());

                for (Object player : recipients) {
                    try {
                        Object buf = FiguraPacketUtil.createBuf();
                        FiguraPacketUtil.writeString(buf, ownerUuid.trim());
                        FiguraPacketUtil.sendS2C(player, "avatar_update", buf);
                    } catch (Throwable t) {
                        FiguraLink.LOGGER.warn("[FiguraLimit] Could not send avatar update packet: " + t.getMessage());
                    }
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
        if (isServerPlayer(handler)) {
            return handler;
        }
        for (String mName : new String[]{"getPlayer", "method_32311"}) {
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

        Class<?> curr = handler.getClass();
        while (curr != null && curr != Object.class) {
            for (Field f : curr.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(handler);
                    if (isServerPlayer(val)) {
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    public static String getEffectiveBackendAddress() {
        String publicAddr = FiguraLinkConfig.getPublicBackendAddress();
        int port = FiguraLinkConfig.getBackendPort();
        if (publicAddr != null && !publicAddr.trim().isEmpty()) {
            return publicAddr.trim();
        } else if (port > 0) {
            return "AUTO:" + port;
        } else {
            // Universal zero-port single link!
            return "127.0.0.1:30007";
        }
    }

    public static void sendViaPacketSender(Object sender) {
        if (sender == null) return;
        try {
            String address = getEffectiveBackendAddress();
            Object channelId = FiguraPacketUtil.createIdentifier("figuralink", "sync_backend");
            Object buf = FiguraPacketUtil.createBuf();
            FiguraPacketUtil.writeString(buf, address);

            // 1. Try createPacket + sendPacket(packet)
            for (Method m : sender.getClass().getMethods()) {
                if ("createPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                    try {
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
                    } catch (Throwable ignored) {}
                }
            }

            // 2. Try sendPacket(channelId, buf)
            for (Method m : sender.getClass().getMethods()) {
                if ("sendPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt[0].isAssignableFrom(channelId.getClass())) {
                        m.invoke(sender, channelId, buf);
                        FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' via PacketSender(Identifier, Buf).", address);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Failed to send via PacketSender: {}", t.getMessage());
        }
    }

    public static void sendBackendAddressToPlayer(Object serverPlayer) {
        if (serverPlayer == null) return;
        String address = getEffectiveBackendAddress();
        Object buf = FiguraPacketUtil.createBuf();
        FiguraPacketUtil.writeString(buf, address);
        FiguraPacketUtil.sendS2C(serverPlayer, "sync_backend", buf);
        FiguraLink.LOGGER.info("[FiguraLimit] Sent backend address '{}' to player.", address);
    }
}
