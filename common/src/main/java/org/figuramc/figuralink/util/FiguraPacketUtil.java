package org.figuramc.figuralink.util;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.client.FiguraClientSync;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class FiguraPacketUtil {

    private static Class<?> cachedIdClass = null;
    private static Class<?> cachedPbbClass = null;

    public static Object createIdentifier(String namespace, String path) {
        try {
            if (cachedIdClass == null) {
                for (String cName : new String[]{
                    "net.minecraft.util.Identifier",
                    "net.minecraft.class_2960",
                    "net.minecraft.resources.Identifier",
                    "net.minecraft.resources.ResourceLocation"
                }) {
                    try {
                        cachedIdClass = Class.forName(cName);
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            if (cachedIdClass != null) {
                Constructor<?> ctor = cachedIdClass.getConstructor(String.class, String.class);
                return ctor.newInstance(namespace, path);
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not create Identifier: " + t.getMessage());
        }
        return null;
    }

    public static Object createBuf() {
        try {
            if (cachedPbbClass == null) {
                try {
                    cachedPbbClass = Class.forName("net.fabricmc.fabric.api.networking.v1.PacketByteBufs");
                } catch (Throwable ignored) {}
            }
            if (cachedPbbClass != null) {
                return cachedPbbClass.getMethod("create").invoke(null);
            }
        } catch (Throwable ignored) {}

        // Fallback: new PacketByteBuf(Unpooled.buffer())
        try {
            Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled");
            Object byteBuf = unpooled.getMethod("buffer").invoke(null);
            for (String cName : new String[]{"net.minecraft.network.PacketByteBuf", "net.minecraft.class_2540"}) {
                try {
                    Class<?> clazz = Class.forName(cName);
                    return clazz.getConstructor(Class.forName("io.netty.buffer.ByteBuf")).newInstance(byteBuf);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Could not create PacketByteBuf: " + t.getMessage());
        }
        return null;
    }

    public static void writeString(Object buf, String str) {
        if (buf == null || str == null) return;
        try {
            buf.getClass().getMethod("writeString", String.class).invoke(buf, str);
            return;
        } catch (Throwable ignored) {}
        try {
            buf.getClass().getMethod("method_10814", String.class).invoke(buf, str);
            return;
        } catch (Throwable ignored) {}

        // ByteBuf fallback
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeInt(buf, bytes.length);
        writeRawBytes(buf, bytes);
    }

    public static String readString(Object buf) {
        if (buf == null) return "";
        try {
            return (String) buf.getClass().getMethod("readString").invoke(buf);
        } catch (Throwable ignored) {}
        try {
            return (String) buf.getClass().getMethod("method_10804").invoke(buf);
        } catch (Throwable ignored) {}

        int len = readInt(buf);
        if (len <= 0 || len > 1024 * 1024) return "";
        byte[] bytes = readRawBytes(buf, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeInt(Object buf, int val) {
        if (buf == null) return;
        try {
            buf.getClass().getMethod("writeInt", int.class).invoke(buf, val);
            return;
        } catch (Throwable ignored) {}
        try {
            buf.getClass().getMethod("method_10816", int.class).invoke(buf, val);
        } catch (Throwable ignored) {}
    }

    public static int readInt(Object buf) {
        if (buf == null) return 0;
        try {
            return (Integer) buf.getClass().getMethod("readInt").invoke(buf);
        } catch (Throwable ignored) {}
        try {
            return (Integer) buf.getClass().getMethod("method_10808").invoke(buf);
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void writeByteArray(Object buf, byte[] data) {
        if (buf == null) return;
        if (data == null) {
            writeInt(buf, 0);
            return;
        }
        writeInt(buf, data.length);
        writeRawBytes(buf, data);
    }

    public static byte[] readByteArray(Object buf) {
        if (buf == null) return new byte[0];
        int len = readInt(buf);
        if (len <= 0 || len > 50 * 1024 * 1024) return new byte[0];
        return readRawBytes(buf, len);
    }

    private static void writeRawBytes(Object buf, byte[] data) {
        try {
            buf.getClass().getMethod("writeBytes", byte[].class).invoke(buf, data);
            return;
        } catch (Throwable ignored) {}
        try {
            buf.getClass().getMethod("method_10817", byte[].class).invoke(buf, data);
        } catch (Throwable ignored) {}
    }

    private static byte[] readRawBytes(Object buf, int len) {
        byte[] res = new byte[len];
        try {
            buf.getClass().getMethod("readBytes", byte[].class).invoke(buf, res);
            return res;
        } catch (Throwable ignored) {}
        try {
            buf.getClass().getMethod("method_10807", byte[].class).invoke(buf, res);
        } catch (Throwable ignored) {}
        return res;
    }

    public static void sendC2S(String channelName, Object buf) {
        if (buf == null) return;
        try {
            Class<?> cpn = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Object channelId = createIdentifier("figuralink", channelName);
            if (channelId == null) return;

            // Try ClientPlayNetworking.send(Identifier, PacketByteBuf)
            for (Method m : cpn.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 2) {
                    try {
                        m.invoke(null, channelId, buf);
                        return;
                    } catch (Throwable ignored) {}
                }
            }

            // Fallback: networkHandler.sendPacket(createC2SPacket(id, buf))
            try {
                Method createC2S = null;
                for (Method m : cpn.getMethods()) {
                    if ("createC2SPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                        createC2S = m;
                        break;
                    }
                }
                if (createC2S != null) {
                    Object packet = createC2S.invoke(null, channelId, buf);
                    Object mc = FiguraClientSync.getMinecraftClientInstance();
                    if (mc != null && packet != null) {
                        Object nh = null;
                        for (String mName : new String[]{"getNetworkHandler", "method_1562"}) {
                            try {
                                nh = mc.getClass().getMethod(mName).invoke(mc);
                                if (nh != null) break;
                            } catch (Throwable ignored) {}
                        }
                        if (nh != null) {
                            for (Method sm : nh.getClass().getMethods()) {
                                if (("sendPacket".equals(sm.getName()) || "method_2883".equals(sm.getName())) && sm.getParameterCount() == 1) {
                                    sm.invoke(nh, packet);
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] sendC2S error for '{}': {}", channelName, t.getMessage());
        }
    }

    public static void sendS2C(Object serverPlayer, String channelName, Object buf) {
        if (serverPlayer == null || buf == null) return;
        try {
            Class<?> spn = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Object channelId = createIdentifier("figuralink", channelName);
            if (channelId == null) return;

            // 1. Direct send via player's networkHandler.sendPacket(createS2CPacket)
            try {
                Method createS2C = null;
                for (Method m : spn.getMethods()) {
                    if ("createS2CPacket".equals(m.getName()) && m.getParameterCount() == 2) {
                        createS2C = m;
                        break;
                    }
                }
                if (createS2C != null) {
                    Object packet = createS2C.invoke(null, channelId, buf);
                    if (packet != null) {
                        Object nh = null;
                        for (String fName : new String[]{"networkHandler", "field_13987"}) {
                            try {
                                Field f = serverPlayer.getClass().getField(fName);
                                nh = f.get(serverPlayer);
                                if (nh != null) break;
                            } catch (Throwable ignored) {}
                        }
                        if (nh != null) {
                            for (Method m : nh.getClass().getMethods()) {
                                if (("sendPacket".equals(m.getName()) || "method_14364".equals(m.getName())) && m.getParameterCount() == 1) {
                                    try {
                                        m.invoke(nh, packet);
                                        return;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Standard ServerPlayNetworking.send fallback
            for (Method m : spn.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterCount() == 3) {
                    try {
                        m.invoke(null, serverPlayer, channelId, buf);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.debug("[FiguraLimit] sendS2C error for '{}': {}", channelName, t.getMessage());
        }
    }
}
