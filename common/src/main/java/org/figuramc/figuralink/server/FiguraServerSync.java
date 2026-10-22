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
                        } catch (Throwable t) {
                            try {
                                player = handler.getClass().getField("field_14384").get(handler);
                            } catch (Throwable ignored) {}
                        }

                        if (player != null) {
                            sendBackendAddressToPlayer(player);
                        }
                    }
                    return null;
                }
            );

            joinEvent.getClass().getMethod("register", Object.class).invoke(joinEvent, listener);
            FiguraLink.LOGGER.info("[FiguraLimit] Registered ServerPlayConnectionEvents.JOIN for backend auto-sync.");
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Server join sender registration skipped: " + t.getMessage());
        }
    }

    public static void sendBackendAddressToPlayer(Object serverPlayer) {
        String publicAddr = FiguraLinkConfig.getPublicBackendAddress();
        if (publicAddr == null || publicAddr.trim().isEmpty()) {
            return;
        }

        String address = publicAddr.trim();
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
                    FiguraLink.LOGGER.info("[FiguraLimit] Sent public backend address '{}' to player via network packet.", address);
                    return;
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] Could not send sync packet: " + t.getMessage());
        }
    }
}
