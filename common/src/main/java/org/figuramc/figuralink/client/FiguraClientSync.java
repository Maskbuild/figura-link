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

        new Thread(() -> {
            try {
                Thread.sleep(1500);
                int port = FiguraLinkConfig.getBackendPort();
                java.net.URL url = java.net.URI.create("http://127.0.0.1:" + port + "/version").toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                if (conn.getResponseCode() == 200) {
                    FiguraLink.LOGGER.info("[FiguraLimit] Startup probe: Found active local backend on 127.0.0.1:{}", port);
                    setFiguraServerIp("127.0.0.1:" + port);
                }
            } catch (Throwable ignored) {}
        }, "FiguraLimit-StartupProbe").start();
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
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Client connection listener skipped: " + t.getMessage());
        }
    }

    public static void switchToServerBackend() {
        String remoteHost = getRemoteServerHost();
        if (remoteHost != null && !remoteHost.isEmpty() && !remoteHost.equals("127.0.0.1") && !remoteHost.equals("localhost")) {
            int port = FiguraLinkConfig.getBackendPort();
            FiguraLink.LOGGER.info("[FiguraLimit] Joined remote server: {}. Pre-setting backend to {}:{}", remoteHost, remoteHost, port);
            setFiguraServerIp(remoteHost + ":" + port);
            return;
        }

        // Otherwise (singleplayer or localhost) connect directly to 127.0.0.1
        int port = FiguraLinkConfig.getBackendPort();
        tryConnectLocal(port);
    }

    private static void tryConnectLocal(int port) {
        try {
            java.net.URL url = java.net.URI.create("http://127.0.0.1:" + port + "/version").toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            if (conn.getResponseCode() == 200) {
                FiguraLink.LOGGER.info("[FiguraLimit] Detected active local backend on 127.0.0.1:{}", port);
                setFiguraServerIp("127.0.0.1:" + port);
            }
        } catch (Throwable ignored) {}
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
            Class<?> mcClass = Class.forName("net.minecraft.client.MinecraftClient");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object serverEntry = mcClass.getMethod("getCurrentServerEntry").invoke(mc);
            if (serverEntry != null) {
                return (String) serverEntry.getClass().getField("address").get(serverEntry);
            }
            Object nh = mcClass.getMethod("getNetworkHandler").invoke(mc);
            if (nh != null) {
                Method getConn = nh.getClass().getMethod("getConnection");
                Object conn = getConn.invoke(nh);
                if (conn != null) {
                    Method getAddr = conn.getClass().getMethod("getAddress");
                    Object addr = getAddr.invoke(conn);
                    if (addr instanceof java.net.InetSocketAddress) {
                        return ((java.net.InetSocketAddress) addr).getHostString();
                    } else if (addr != null) {
                        return addr.toString().replaceAll("^/+", "");
                    }
                }
            }
        } catch (Throwable t) {
            try {
                Class<?> mcClass = Class.forName("net.minecraft.class_310");
                Object mc = mcClass.getMethod("method_1551").invoke(null);
                Object serverEntry = mcClass.getMethod("method_1558").invoke(mc);
                if (serverEntry != null) {
                    return (String) serverEntry.getClass().getField("field_3761").get(serverEntry);
                }
                Object nh = mcClass.getMethod("method_1562").invoke(mc);
                if (nh != null) {
                    Method getConn = nh.getClass().getMethod("method_10755");
                    Object conn = getConn.invoke(nh);
                    if (conn != null) {
                        Method getAddr = conn.getClass().getMethod("method_10756");
                        Object addr = getAddr.invoke(conn);
                        if (addr instanceof java.net.InetSocketAddress) {
                            return ((java.net.InetSocketAddress) addr).getHostString();
                        } else if (addr != null) {
                            return addr.toString().replaceAll("^/+", "");
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
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
