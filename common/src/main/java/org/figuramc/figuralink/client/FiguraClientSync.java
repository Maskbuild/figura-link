package org.figuramc.figuralink.client;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.UUID;

public class FiguraClientSync {

    public static final String OFFICIAL_FIGURA_IP = "figura.moonlight-devs.org";

    public static void init() {
        if (!FiguraLinkConfig.isClientEnvironment()) {
            return;
        }

        registerClientReceiver();
        registerAvatarUpdateReceiver();
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
                            address = address.trim();
                            if (address.startsWith("AUTO:") || address.startsWith("127.0.0.1:") || address.startsWith("localhost:") || address.startsWith(":")) {
                                String remoteHost = getRemoteServerHost();
                                if (remoteHost != null && !remoteHost.isEmpty() && !remoteHost.equals("127.0.0.1") && !remoteHost.equals("localhost")) {
                                    int colon = address.lastIndexOf(':');
                                    String port = (colon != -1) ? address.substring(colon) : (":" + FiguraLinkConfig.getBackendPort());
                                    address = remoteHost + port;
                                } else if (address.startsWith("AUTO:")) {
                                    address = "127.0.0.1:" + address.substring(5);
                                }
                            }
                            FiguraLink.LOGGER.info("[FiguraLimit] Received server backend address from network packet: {}", address);
                            setFiguraServerIp(address);
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

    private static void registerAvatarUpdateReceiver() {
        try {
            Class<?> cpn = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> idClass = null;
            try { idClass = Class.forName("net.minecraft.util.Identifier"); } 
            catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }

            Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "avatar_update");

            Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler");
            Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null) {
                        Object buf = null;
                        for (Object arg : args) {
                            if (arg != null && arg.getClass().getName().contains("Buf")) {
                                buf = arg;
                                break;
                            }
                        }
                        if (buf == null && args.length >= 3) {
                            buf = args[2];
                        }
                        if (buf == null) return null;

                        String ownerUuidStr = null;
                        try {
                            ownerUuidStr = (String) buf.getClass().getMethod("readString").invoke(buf);
                        } catch (Throwable t) {
                            try {
                                ownerUuidStr = (String) buf.getClass().getMethod("method_10804").invoke(buf);
                            } catch (Throwable ignored) {}
                        }

                        if (ownerUuidStr == null || ownerUuidStr.trim().isEmpty()) return null;
                        final String finalUuid = ownerUuidStr.trim();
                        FiguraLink.LOGGER.info("[FiguraLimit] Received avatar update packet for: {}", finalUuid);
                        final UUID targetUUID = parseUUID(finalUuid);
                        if (targetUUID == null) {
                            FiguraLink.LOGGER.warn("[FiguraLimit] Could not parse UUID from update packet: {}", finalUuid);
                            return null;
                        }

                        try {
                            Class<?> mcClass = null;
                            try { mcClass = Class.forName("net.minecraft.client.MinecraftClient"); }
                            catch (Throwable e) { mcClass = Class.forName("net.minecraft.class_310"); }
                            Object mc = mcClass.getMethod("getInstance").invoke(null);
                            if (mc != null) {
                                Method exec = mc.getClass().getMethod("execute", Runnable.class);
                                exec.invoke(mc, (Runnable) () -> {
                                    try {
                                        Class<?> fm = Class.forName("org.figuramc.figura.FiguraMod");
                                        Object localUuid = fm.getMethod("getLocalPlayerUUID").invoke(null);
                                        if (targetUUID.equals(localUuid)) {
                                            Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                                            try {
                                                Field lu = am.getDeclaredField("localUploaded");
                                                lu.setAccessible(true);
                                                lu.setBoolean(null, true);
                                            } catch (Throwable ignored) {}
                                            FiguraLink.LOGGER.info("[FiguraLimit] Avatar update packet is for local player: keeping local avatar.");
                                            return;
                                        }

                                        Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                                        
                                        try {
                                            Method clear = am.getMethod("clearAvatars", UUID.class);
                                            clear.invoke(null, targetUUID);
                                        } catch (Throwable ignored) {}

                                        try {
                                            Method fetchBackend = am.getDeclaredMethod("fetchBackend", UUID.class);
                                            fetchBackend.setAccessible(true);
                                            fetchBackend.invoke(null, targetUUID);
                                            FiguraLink.LOGGER.info("[FiguraLimit] Successfully triggered fetchBackend for remote player: {}", targetUUID);
                                        } catch (Throwable t) {
                                            try {
                                                Method reload = am.getMethod("reloadAvatar", UUID.class);
                                                reload.invoke(null, targetUUID);
                                            } catch (Throwable ignored) {}
                                        }

                                        FiguraLink.LOGGER.info("[FiguraLimit] Reloaded avatar cache for: {}", targetUUID);
                                    } catch (Throwable ignored) {}
                                });
                            }
                        } catch (Throwable t) {
                            try {
                                Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                                try {
                                    Method clear = am.getMethod("clearAvatars", UUID.class);
                                    clear.invoke(null, targetUUID);
                                } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}
                        }
                    }
                    return null;
                }
            );

            Method regMethod = cpn.getMethod("registerGlobalReceiver", idClass, handlerClass);
            regMethod.invoke(null, channelId, handler);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayNetworking receiver for 'figuralink:avatar_update'");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Avatar update receiver skipped: " + t.getMessage());
        }
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

    private static void registerClientAutoSwitch() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Object joinEvent = eventsClass.getField("JOIN").get(null);

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
                FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayConnectionEvents.JOIN for client auto-switch.");
            }

            // Register DISCONNECT event to restore official Figura cloud
            try {
                Object disconnectEvent = eventsClass.getField("DISCONNECT").get(null);
                Class<?> disconnectClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect");
                Object disconnectListener = Proxy.newProxyInstance(
                    disconnectClass.getClassLoader(),
                    new Class<?>[]{disconnectClass},
                    (proxy, method, args) -> {
                        FiguraLink.LOGGER.info("[FiguraLimit] Client disconnected from server: restoring official Figura backend.");
                        restoreOfficialBackend();
                        return null;
                    }
                );
                if (regMethod != null) {
                    regMethod.invoke(disconnectEvent, disconnectListener);
                    FiguraLink.LOGGER.info("[FiguraLimit] Registered ClientPlayConnectionEvents.DISCONNECT to restore official backend.");
                }
            } catch (Throwable t) {
                FiguraLink.LOGGER.debug("[FiguraLimit] Client disconnect listener registration: " + t.getMessage());
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Client connection listener skipped: " + t.getMessage());
        }
    }

    public static void switchToServerBackend() {
        new Thread(() -> {
            String remoteHost = null;
            for (int i = 0; i < 15; i++) {
                if (isSingleplayer()) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Singleplayer world detected: using official Figura cloud.");
                    restoreOfficialBackend();
                    return;
                }
                remoteHost = getRemoteServerHost();
                if (remoteHost != null && !remoteHost.isEmpty() && !remoteHost.equals("127.0.0.1") && !remoteHost.equals("localhost")) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }

            if (remoteHost != null && !remoteHost.isEmpty() && !remoteHost.equals("127.0.0.1") && !remoteHost.equals("localhost")) {
                FiguraLink.LOGGER.info("[FiguraLimit] Joined remote server: {}. Auto-switching to server backend...", remoteHost);
                
                // Immediately switch to remoteHost:<backendPort>
                String targetAddress = remoteHost + ":" + FiguraLinkConfig.getBackendPort();
                setFiguraServerIp(targetAddress);

                // Send request packet to server in case server has custom port / public tunnel
                requestBackendFromServer();

                // Also run background HTTP probe to discover active backend port if non-default
                probeBackendPorts(remoteHost);
                return;
            }

            // Singleplayer or local world: ALWAYS use official Figura Cloud
            FiguraLink.LOGGER.info("[FiguraLimit] Singleplayer/private world detected: using official Figura cloud.");
            restoreOfficialBackend();
        }, "FiguraLimit-AutoSwitch").start();
    }

    private static void probeBackendPorts(String remoteHost) {
        new Thread(() -> {
            int currentPort = FiguraLinkConfig.getBackendPort();
            int[] portsToTest = new int[]{currentPort, 7070, 25566, 25565};
            for (int port : portsToTest) {
                try {
                    URL url = new URL("http://" + remoteHost + ":" + port + "/version");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = conn.getInputStream()) {
                            byte[] bytes = in.readAllBytes();
                            String text = new String(bytes);
                            if (text.contains("release") || text.contains("0.1.6") || text.contains("figura")) {
                                String workingAddr = remoteHost + ":" + port;
                                FiguraLink.LOGGER.info("[FiguraLimit] HTTP probe verified active Figura backend at: {}", workingAddr);
                                setFiguraServerIp(workingAddr);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, "FiguraLimit-PortProbe").start();
    }

    public static void requestBackendFromServer() {
        new Thread(() -> {
            for (int delay : new int[]{100, 600, 1500, 3000}) {
                try {
                    Thread.sleep(delay);
                    Class<?> cpn = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
                    Class<?> idClass = null;
                    try { idClass = Class.forName("net.minecraft.util.Identifier"); }
                    catch (Throwable e) { idClass = Class.forName("net.minecraft.class_2960"); }
                    Object channelId = idClass.getConstructor(String.class, String.class).newInstance("figuralink", "request_backend");

                    Class<?> pbb = Class.forName("net.fabricmc.fabric.api.networking.v1.PacketByteBufs");
                    Object buf = pbb.getMethod("create").invoke(null);

                    for (Method m : cpn.getMethods()) {
                        if ("send".equals(m.getName()) && m.getParameterCount() == 2) {
                            m.invoke(null, channelId, buf);
                            FiguraLink.LOGGER.debug("[FiguraLimit] Sent request_backend packet to server.");
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, "FiguraLimit-RequestBackend").start();
    }

    public static void restoreOfficialBackend() {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            String current = (String) serverIpConfig.getClass().getField("value").get(serverIpConfig);
            if (!OFFICIAL_FIGURA_IP.equals(current)) {
                Method setValue = serverIpConfig.getClass().getMethod("setValue", String.class);
                setValue.invoke(serverIpConfig, OFFICIAL_FIGURA_IP);
                FiguraLink.LOGGER.info("[FiguraLimit] Restored official Figura Cloud IP: {}", OFFICIAL_FIGURA_IP);

                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                ns.getMethod("reAuth").invoke(null);
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Failed to restore official Figura Cloud IP: " + t.getMessage());
        }
    }

    public static boolean isSingleplayer() {
        try {
            Object mc = getMinecraftClientInstance();
            if (mc == null) return false;

            // 1. isIntegratedServerRunning()
            for (String mName : new String[]{"isIntegratedServerRunning", "method_1496"}) {
                try {
                    Method m = mc.getClass().getMethod(mName);
                    Object res = m.invoke(mc);
                    if (res instanceof Boolean && (Boolean) res) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }

            // 2. getServer() / isLocalServer()
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

    private static Object getMinecraftClientInstance() {
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

    public static String getRemoteServerHost() {
        String raw = getRemoteServerAddress();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        raw = raw.trim();
        if (raw.contains(":")) {
            raw = raw.substring(0, raw.indexOf(':'));
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    public static String getRemoteServerAddress() {
        try {
            Object mc = getMinecraftClientInstance();
            if (mc == null) return null;

            if (isSingleplayer()) {
                return null;
            }

            // 1. Check ServerInfo (getCurrentServerEntry / method_1558)
            Object serverEntry = null;
            for (String mName : new String[]{"getCurrentServerEntry", "method_1558"}) {
                try {
                    Method m = mc.getClass().getMethod(mName);
                    serverEntry = m.invoke(mc);
                    if (serverEntry != null) break;
                } catch (Throwable ignored) {}
            }

            if (serverEntry != null) {
                for (String fieldName : new String[]{"field_3761", "address", "b"}) {
                    try {
                        Field f = serverEntry.getClass().getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object val = f.get(serverEntry);
                        if (val instanceof String && !((String) val).trim().isEmpty()) {
                            return ((String) val).trim();
                        }
                    } catch (Throwable ignored) {}
                }
                for (Field f : serverEntry.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        try {
                            f.setAccessible(true);
                            String s = (String) f.get(serverEntry);
                            if (s != null && (s.contains(".") || s.contains(":"))) {
                                return s.trim();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // 2. Check NetworkHandler connection address
            Object nh = null;
            for (String mName : new String[]{"getNetworkHandler", "method_1562"}) {
                try {
                    Method m = mc.getClass().getMethod(mName);
                    nh = m.invoke(mc);
                    if (nh != null) break;
                } catch (Throwable ignored) {}
            }

            if (nh != null) {
                Object conn = null;
                for (String mName : new String[]{"getConnection", "method_48296"}) {
                    try {
                        Method m = nh.getClass().getMethod(mName);
                        conn = m.invoke(nh);
                        if (conn != null) break;
                    } catch (Throwable ignored) {}
                }

                if (conn == null) {
                    for (Field f : nh.getClass().getDeclaredFields()) {
                        String typeName = f.getType().getName();
                        if (typeName.endsWith("ClientConnection") || typeName.endsWith("class_2535") || typeName.equals("sd")) {
                            try {
                                f.setAccessible(true);
                                conn = f.get(nh);
                                if (conn != null) break;
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                if (conn != null) {
                    Object addr = null;
                    for (String mName : new String[]{"getAddress", "method_10755"}) {
                        try {
                            Method m = conn.getClass().getMethod(mName);
                            addr = m.invoke(conn);
                            if (addr != null) break;
                        } catch (Throwable ignored) {}
                    }

                    if (addr instanceof InetSocketAddress) {
                        InetSocketAddress isa = (InetSocketAddress) addr;
                        String host = isa.getHostString();
                        int port = isa.getPort();
                        return (port > 0 && port != 25565) ? (host + ":" + port) : host;
                    } else if (addr != null) {
                        return addr.toString().replaceAll("^/+", "");
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean setFiguraServerIp(String target) {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            String current = (String) serverIpConfig.getClass().getField("value").get(serverIpConfig);
            if (target.equals(current)) {
                return true;
            }

            Method setValue = serverIpConfig.getClass().getMethod("setValue", String.class);
            setValue.invoke(serverIpConfig, target);

            FiguraLink.LOGGER.info("[FiguraLimit] Automatically updated Figura Cloud IP to: {}", target);

            // Trigger re-authentication with new backend
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
            FiguraLink.LOGGER.warn("[FiguraLimit] Failed to update Figura Cloud IP to " + target + ": " + t.getMessage());
            return false;
        }
    }
}
