package org.figuramc.figuralink.client;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.server.EmbeddedFiguraServer;
import org.figuramc.figuralink.util.AvatarTransferTracker;
import org.figuramc.figuralink.util.FiguraPacketUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FiguraClientSync {

    public static final String UNIFIED_LOCAL_LINK = "127.0.0.1:30007";
    public static final String OFFICIAL_FIGURA_IP = "figura.moonlight-devs.org";
    public static volatile long lastSyncPacketTime = 0;
    private static final AvatarTransferTracker clientTracker = new AvatarTransferTracker();

    public static void init() {
        if (!FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        // Set unified single link immediately
        setFiguraServerIp(UNIFIED_LOCAL_LINK);

        registerClientReceivers();
        registerClientConnectionEvents();
    }

    private interface ClientPacketReceiver {
        void onPacket(Object buf);
    }

    private static void registerClientReceivers() {
        try {
            Class<?> cpn = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); } 
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler");
            Method regMethod = cpn.getMethod("registerGlobalReceiver", idClass, handlerClass);

            // 1. sync_backend
            registerOneReceiver(cpn, idClass, handlerClass, regMethod, "sync_backend", buf -> {
                String address = FiguraPacketUtil.readString(buf);
                if (address != null && !address.trim().isEmpty()) {
                    address = address.trim();
                    lastSyncPacketTime = System.currentTimeMillis();
                    FiguraLink.LOGGER.info("[FiguraLimit] Received server backend sync packet: {}", address);
                    if (address.equals("127.0.0.1:30007") || address.startsWith("AUTO:") || address.startsWith("127.0.0.1:") || address.startsWith("localhost:")) {
                        setFiguraServerIp(UNIFIED_LOCAL_LINK);
                    } else {
                        // User or server explicitly specified an external public domain
                        setFiguraServerIp(address);
                    }
                }
            });

            // 2. avatar_update
            registerOneReceiver(cpn, idClass, handlerClass, regMethod, "avatar_update", buf -> {
                String ownerUuidStr = FiguraPacketUtil.readString(buf);
                if (ownerUuidStr == null || ownerUuidStr.trim().isEmpty()) return;
                final String finalUuid = ownerUuidStr.trim();
                final UUID targetUUID = parseUUID(finalUuid);
                if (targetUUID == null) return;

                Object mc = getMinecraftClientInstance();
                UUID localUuid = getLocalPlayerUuid();
                if (localUuid != null && targetUUID.equals(localUuid)) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Avatar update is for local player: maintaining local state.");
                    return;
                }

                // Check if we have the avatar file locally
                byte[] localData = EmbeddedFiguraServer.getAvatarBytes(finalUuid);
                if (localData == null || localData.length == 0) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Avatar update for {}: file missing locally, requesting from server...", finalUuid);
                    requestAvatarFromServer(finalUuid);
                } else {
                    reloadRemotePlayerAvatar(mc, targetUUID);
                }
            });

            // 3. avatar_chunk (S2C received avatar chunk)
            registerOneReceiver(cpn, idClass, handlerClass, regMethod, "avatar_chunk", buf -> {
                String ownerUuid = FiguraPacketUtil.readString(buf);
                String hash = FiguraPacketUtil.readString(buf);
                int chunkIndex = FiguraPacketUtil.readInt(buf);
                int totalChunks = FiguraPacketUtil.readInt(buf);
                byte[] chunkData = FiguraPacketUtil.readByteArray(buf);

                byte[] fullAvatar = clientTracker.handleIncomingChunk(ownerUuid, hash, chunkIndex, totalChunks, chunkData);
                if (fullAvatar != null) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Fully received avatar for {} (size: {} bytes). Saving...", ownerUuid, fullAvatar.length);
                    EmbeddedFiguraServer.saveAvatarFile(ownerUuid, hash, fullAvatar);

                    Object mc = getMinecraftClientInstance();
                    UUID targetUUID = parseUUID(ownerUuid);
                    UUID localUuid = getLocalPlayerUuid();
                    if (localUuid != null && targetUUID != null && localUuid.equals(targetUUID)) {
                        FiguraLink.LOGGER.info("[FiguraLimit] Downloaded avatar is local player's own avatar.");
                        return;
                    }
                    if (targetUUID != null) {
                        reloadRemotePlayerAvatar(mc, targetUUID);
                    }
                }
            });

            // 4. request_avatar (Server requests local avatar from client)
            registerOneReceiver(cpn, idClass, handlerClass, regMethod, "request_avatar", buf -> {
                String reqUuid = FiguraPacketUtil.readString(buf);
                UUID localUuid = getLocalPlayerUuid();
                if (reqUuid != null && localUuid != null && reqUuid.trim().equalsIgnoreCase(localUuid.toString())) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Server requested our local avatar. Streaming chunks to server...");
                    byte[] data = EmbeddedFiguraServer.getAvatarBytes(reqUuid.trim());
                    if (data != null && data.length > 0) {
                        String hash = EmbeddedFiguraServer.getUserEquipped(reqUuid.trim());
                        sendAvatarChunksToServer(reqUuid.trim(), hash, data);
                    }
                }
            });

        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Client networking registration skipped: " + t.getMessage());
        }
    }

    private static void registerOneReceiver(Class<?> cpn, Class<?> idClass, Class<?> handlerClass, Method regMethod, String channelName, ClientPacketReceiver receiver) {
        try {
            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", channelName);
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
                        if (receiver != null && buf != null) {
                            receiver.onPacket(buf);
                        }
                    }
                    return null;
                }
            );
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered Client receiver for 'figuralink:{}'", channelName);
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Failed to register client receiver for '{}': {}", channelName, t.getMessage());
        }
    }

    private static void registerClientConnectionEvents() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Object joinEvent = eventsClass.getField("JOIN").get(null);

            Class<?> listenerClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Join");
            Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if ("onPlayReady".equals(method.getName())) {
                        onJoinServer();
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
                FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayConnectionEvents.JOIN.");
            }

            // Register DISCONNECT event
            try {
                Object disconnectEvent = eventsClass.getField("DISCONNECT").get(null);
                Class<?> disconnectClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect");
                Object disconnectListener = Proxy.newProxyInstance(
                    disconnectClass.getClassLoader(),
                    new Class<?>[]{disconnectClass},
                    (proxy, method, args) -> {
                        FiguraLink.LOGGER.info("[FiguraLimit] Client disconnected from server: maintaining unified local link.");
                        setFiguraServerIp(UNIFIED_LOCAL_LINK);
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

    public static void onJoinServer() {
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // 1. Ensure unified single link
            setFiguraServerIp(UNIFIED_LOCAL_LINK);

            if (isSingleplayer()) {
                FiguraLink.LOGGER.info("[FiguraLimit] Singleplayer world: local backend active on {}", UNIFIED_LOCAL_LINK);
                return;
            }

            // 2. Request backend sync packet from server
            requestBackendFromServer();

            // 3. Send handshake with our local player UUID and equipped avatar hash
            UUID localUuid = getLocalPlayerUuid();
            if (localUuid != null) {
                String hash = EmbeddedFiguraServer.getUserEquipped(localUuid.toString());
                if (hash == null) hash = "";

                FiguraLink.LOGGER.info("[FiguraLimit] Sending handshake to server for local player {} (hash: '{}')", localUuid, hash);
                Object buf = FiguraPacketUtil.createBuf();
                FiguraPacketUtil.writeString(buf, localUuid.toString());
                FiguraPacketUtil.writeString(buf, hash);
                FiguraPacketUtil.sendC2S("handshake", buf);
            }
        }, "FiguraLimit-OnJoinServer").start();
    }

    public static void onLocalAvatarSaved(String ownerUuid, byte[] data, String hash) {
        if (ownerUuid == null || data == null || data.length == 0) return;
        if (isSingleplayer()) {
            FiguraLink.LOGGER.info("[FiguraLimit] Avatar saved in singleplayer. Local storage updated.");
            return;
        }

        FiguraLink.LOGGER.info("[FiguraLimit] Local avatar saved for {}. Streaming avatar chunks to server...", ownerUuid);
        new Thread(() -> {
            sendAvatarChunksToServer(ownerUuid, hash, data);
        }, "FiguraLimit-UploadChunks").start();
    }

    public static void onLocalAvatarEquipped(String ownerUuid) {
        if (ownerUuid == null || isSingleplayer()) return;
        String hash = EmbeddedFiguraServer.getUserEquipped(ownerUuid);
        if (hash == null) hash = "avatar";

        FiguraLink.LOGGER.info("[FiguraLimit] Local avatar equipped for {}. Sending handshake to server...", ownerUuid);
        Object buf = FiguraPacketUtil.createBuf();
        FiguraPacketUtil.writeString(buf, ownerUuid);
        FiguraPacketUtil.writeString(buf, hash);
        FiguraPacketUtil.sendC2S("handshake", buf);
    }

    public static void sendAvatarChunksToServer(String ownerUuid, String hash, byte[] data) {
        if (ownerUuid == null || data == null || data.length == 0) return;
        AvatarTransferTracker.streamChunks(ownerUuid, hash, data, (uuid, h, chunkIdx, totalChunks, chunk) -> {
            Object buf = FiguraPacketUtil.createBuf();
            FiguraPacketUtil.writeString(buf, uuid);
            FiguraPacketUtil.writeString(buf, h);
            FiguraPacketUtil.writeInt(buf, chunkIdx);
            FiguraPacketUtil.writeInt(buf, totalChunks);
            FiguraPacketUtil.writeByteArray(buf, chunk);
            FiguraPacketUtil.sendC2S("avatar_chunk", buf);
        });
    }

    public static void requestAvatarFromServer(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.trim().isEmpty()) return;
        Object buf = FiguraPacketUtil.createBuf();
        FiguraPacketUtil.writeString(buf, ownerUuid.trim());
        FiguraPacketUtil.sendC2S("request_avatar", buf);
    }

    public static void requestBackendFromServer() {
        for (int delay : new int[]{0, 200, 600, 1500}) {
            try {
                if (delay > 0) Thread.sleep(delay);
                Object buf = FiguraPacketUtil.createBuf();
                FiguraPacketUtil.sendC2S("request_backend", buf);
                FiguraLink.LOGGER.debug("[FiguraLimit] Sent request_backend packet to server.");
            } catch (Throwable ignored) {}
        }
    }

    public static boolean setFiguraServerIp(String target) {
        if (target == null || target.trim().isEmpty()) {
            target = UNIFIED_LOCAL_LINK;
        }
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            String current = (String) serverIpConfig.getClass().getField("value").get(serverIpConfig);
            if (target.equals(current)) {
                return true;
            }

            Method setValue = serverIpConfig.getClass().getMethod("setValue", String.class);
            setValue.invoke(serverIpConfig, target);

            FiguraLink.LOGGER.info("[FiguraLimit] Configured Figura Server Link to: {}", target);

            // Trigger re-authentication with backend
            Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
            ns.getMethod("reAuth").invoke(null);

            // Reset localUploaded in AvatarManager so upload button is ready
            try {
                Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                Field lu = am.getDeclaredField("localUploaded");
                lu.setAccessible(true);
                lu.setBoolean(null, false);
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Failed to set Figura Server IP to " + target + ": " + t.getMessage());
            return false;
        }
    }

    public static UUID getLocalPlayerUuid() {
        try {
            Class<?> fm = Class.forName("org.figuramc.figura.FiguraMod");
            Object u = fm.getMethod("getLocalPlayerUUID").invoke(null);
            if (u instanceof UUID) return (UUID) u;
        } catch (Throwable ignored) {}

        Object mc = getMinecraftClientInstance();
        if (mc != null) {
            try {
                for (String mName : new String[]{"getSession", "method_1548"}) {
                    try {
                        Object session = mc.getClass().getMethod(mName).invoke(mc);
                        if (session != null) {
                            for (String smName : new String[]{"getUuidOrNull", "getProfileId", "method_1676"}) {
                                try {
                                    Object id = session.getClass().getMethod(smName).invoke(session);
                                    if (id instanceof UUID) return (UUID) id;
                                    if (id instanceof String) return parseUUID((String) id);
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static UUID parseUUID(String s) {
        if (s == null) return null;
        s = s.trim();
        try {
            if (s.contains("-")) {
                return UUID.fromString(s);
            } else if (s.length() == 32) {
                return UUID.fromString(s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            }
            return UUID.fromString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void reloadRemotePlayerAvatar(Object mc, UUID targetUUID) {
        if (mc == null || targetUUID == null) return;
        try {
            Method exec = mc.getClass().getMethod("execute", Runnable.class);
            exec.invoke(mc, (Runnable) () -> {
                try {
                    Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                    try {
                        Method clear = am.getMethod("clearAvatars", UUID.class);
                        clear.invoke(null, targetUUID);
                    } catch (Throwable ignored) {}

                    try {
                        Method fetchBackend = am.getDeclaredMethod("fetchBackend", UUID.class);
                        fetchBackend.setAccessible(true);
                        fetchBackend.invoke(null, targetUUID);
                        FiguraLink.LOGGER.info("[FiguraLimit] Triggered fetchBackend for remote player: {}", targetUUID);
                    } catch (Throwable t) {
                        try {
                            Method reload = am.getMethod("reloadAvatar", UUID.class);
                            reload.invoke(null, targetUUID);
                        } catch (Throwable ignored) {}
                    }

                    FiguraLink.LOGGER.info("[FiguraLimit] Successfully refreshed avatar for player: {}", targetUUID);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    public static boolean isSingleplayer() {
        try {
            Object mc = getMinecraftClientInstance();
            if (mc == null) return false;

            for (String mName : new String[]{"isIntegratedServerRunning", "method_1496"}) {
                try {
                    Method m = mc.getClass().getMethod(mName);
                    Object res = m.invoke(mc);
                    if (res instanceof Boolean && (Boolean) res) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }

            for (String mName : new String[]{"getServer", "method_1576"}) {
                try {
                    Method m = mc.getClass().getMethod(mName);
                    if (m.invoke(mc) != null) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static Object getMinecraftClientInstance() {
        for (String cName : new String[]{"net.minecraft.client.MinecraftClient", "net.minecraft.class_310"}) {
            try {
                Class<?> clazz = Class.forName(cName);
                for (String mName : new String[]{"getInstance", "method_1551"}) {
                    try {
                        Method m = clazz.getMethod(mName);
                        Object inst = m.invoke(null);
                        if (inst != null) return inst;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void switchToServerBackend() {
        onJoinServer();
    }

    public static String getRemoteServerHost() {
        return "127.0.0.1";
    }

    public static void sendToggleDrivePacket() {
        try {
            Object buf = FiguraPacketUtil.createBuf();
            FiguraPacketUtil.sendC2S("toggle_drive", buf);
            FiguraLink.LOGGER.debug("[FiguraLimit] Sent toggle_drive packet to server.");
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not send toggle_drive packet: " + t.getMessage());
        }
    }
}
