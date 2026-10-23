package org.figuramc.figuralink.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.figuramc.figuralink.client.FiguraClientSync;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.server.FiguraDriveBossBar;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;

public class FiguraLimitCommand {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerSubcommand(LiteralArgumentBuilder root) {
        // 1. /figura limit-test
        root.then(LiteralArgumentBuilder.literal("limit-test")
            .requires(FiguraLimitCommand::isOp)
            .executes(context -> {
                Object source = context.getSource();
                if (!isOp(source)) {
                    sendFeedback(source, "You do not have permission to use this command.");
                    return 0;
                }

                if (checkConnected()) {
                    sendFeedback(source, "hello world");
                } else {
                    sendFeedback(source, "Figura backend not connected");
                }
                return 1;
            })
        );

        // 2. /figura link-server (manual trigger to link to server backend)
        root.then(LiteralArgumentBuilder.literal("link-server")
            .executes(context -> {
                Object source = context.getSource();
                FiguraClientSync.switchToServerBackend();
                sendFeedback(source, "[FiguraLimit] Switched Figura Cloud IP to server backend (port " + FiguraLinkConfig.getBackendPort() + ")");
                return 1;
            })
            .then(RequiredArgumentBuilder.argument("address", StringArgumentType.greedyString())
                .executes(context -> {
                    Object source = context.getSource();
                    String address = StringArgumentType.getString(context, "address");
                    FiguraClientSync.setFiguraServerIp(address);
                    sendFeedback(source, "[FiguraLimit] Switched Figura Cloud IP to: " + address);
                    return 1;
                })
            )
        );

        // 3. /figura drive (toggle storage BossBar, OP only)
        root.then(LiteralArgumentBuilder.literal("drive")
            .requires(FiguraLimitCommand::isOp)
            .executes(context -> {
                Object source = context.getSource();
                if (!isOp(source)) {
                    sendFeedback(source, "§cYou do not have permission to use this command.");
                    return 0;
                }
                handleDrive(source);
                return 1;
            })
        );
    }

    private static void handleDrive(Object source) {
        if (source == null) return;

        // 1. Check if source is a ServerCommandSource with a player
        Object serverPlayer = null;
        for (String mName : new String[]{"getPlayer", "method_9207"}) {
            try {
                Method m = source.getClass().getMethod(mName);
                Object p = m.invoke(source);
                if (p != null) {
                    serverPlayer = p;
                    break;
                }
            } catch (Throwable ignored) {}
        }

        if (serverPlayer != null) {
            FiguraDriveBossBar.togglePlayer(serverPlayer, source);
            return;
        }

        // 2. Check if source is server console
        if (source.getClass().getName().contains("ServerCommandSource") || source.getClass().getName().contains("class_2168")) {
            FiguraDriveBossBar.printConsoleStats(source);
            return;
        }

        // 3. Client environment
        if (FiguraClientSync.isSingleplayer()) {
            try {
                Object mc = FiguraClientSync.getMinecraftClientInstance();
                if (mc != null) {
                    Object server = null;
                    for (String mName : new String[]{"getServer", "method_1576"}) {
                        try {
                            server = mc.getClass().getMethod(mName).invoke(mc);
                            if (server != null) break;
                        } catch (Throwable ignored) {}
                    }
                    if (server != null) {
                        Object pm = null;
                        for (Method m : server.getClass().getMethods()) {
                            if (m.getParameterCount() == 0 && (
                                m.getReturnType().getName().contains("PlayerManager") || 
                                m.getReturnType().getName().endsWith("class_3324"))) {
                                pm = m.invoke(server);
                                break;
                            }
                        }
                        if (pm != null) {
                            UUID localUuid = null;
                            try {
                                Class<?> fm = Class.forName("org.figuramc.figura.FiguraMod");
                                Object u = fm.getMethod("getLocalPlayerUUID").invoke(null);
                                if (u instanceof UUID) localUuid = (UUID) u;
                            } catch (Throwable ignored) {}

                            if (localUuid != null) {
                                for (String mName : new String[]{"getPlayer", "method_14602"}) {
                                    try {
                                        Object p = pm.getClass().getMethod(mName, UUID.class).invoke(pm, localUuid);
                                        if (p != null) {
                                            FiguraDriveBossBar.togglePlayer(p, source);
                                            return;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // In multiplayer client: send packet to server
        FiguraClientSync.sendToggleDrivePacket();
    }

    public static boolean checkConnected() {
        try {
            Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
            Method isConn = ns.getMethod("isConnected");
            return (Boolean) isConn.invoke(null);
        } catch (Throwable ignored) {
            // On dedicated server where NetworkStuff is absent, mod itself is active on server
            return true;
        }
    }

    public static boolean isOp(Object source) {
        if (source == null) return false;
        try {
            Method m = source.getClass().getMethod("hasPermissionLevel", int.class);
            return (Boolean) m.invoke(source, 2);
        } catch (Throwable ignored) {}
        try {
            Method m = source.getClass().getMethod("method_5687", int.class);
            return (Boolean) m.invoke(source, 2);
        } catch (Throwable ignored) {}
        try {
            Method m = source.getClass().getMethod("method_9259", int.class);
            return (Boolean) m.invoke(source, 2);
        } catch (Throwable ignored) {}
        try {
            Method getPlayer = source.getClass().getMethod("figura$getPlayer");
            Object player = getPlayer.invoke(source);
            if (player != null) {
                return isOp(player);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static void sendFeedback(Object source, String message) {
        if (source == null) return;
        // 1. FiguraClientCommandSource: figura$sendFeedback
        try {
            Class<?> textClass = Class.forName("net.minecraft.class_2561");
            Method fb = source.getClass().getMethod("figura$sendFeedback", textClass);
            Object text = Class.forName("org.figuramc.figura.utils.FiguraText").getMethod("of", String.class).invoke(null, message);
            fb.invoke(source, text);
            return;
        } catch (Throwable ignored) {}

        try {
            Class<?> textClass = Class.forName("net.minecraft.text.Text");
            Method fb = source.getClass().getMethod("figura$sendFeedback", textClass);
            Object text = textClass.getMethod("literal", String.class).invoke(null, message);
            fb.invoke(source, text);
            return;
        } catch (Throwable ignored) {}

        // 2. ServerCommandSource: sendFeedback
        for (Method m : source.getClass().getMethods()) {
            if (m.getName().toLowerCase().contains("feedback") || m.getName().toLowerCase().contains("sendmessage")) {
                if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class) {
                    try {
                        Class<?> textClass = Class.forName("net.minecraft.class_2561");
                        Object text = textClass.getMethod("method_43470", String.class).invoke(null, message);
                        Supplier<?> s = () -> text;
                        m.invoke(source, s, false);
                        return;
                    } catch (Throwable ignored) {}
                    try {
                        Class<?> textClass = Class.forName("net.minecraft.text.Text");
                        Object text = textClass.getMethod("literal", String.class).invoke(null, message);
                        Supplier<?> s = () -> text;
                        m.invoke(source, s, false);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
    }
}
