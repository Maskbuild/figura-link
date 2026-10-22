package org.figuramc.figuralink.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public class FiguraLimitCommand {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerSubcommand(LiteralArgumentBuilder root) {
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
