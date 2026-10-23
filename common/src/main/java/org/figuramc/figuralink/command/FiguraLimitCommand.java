package org.figuramc.figuralink.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.figuramc.figuralink.client.FiguraClientSync;
import org.figuramc.figuralink.util.FiguraLinkUtil;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public class FiguraLimitCommand {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerSubcommand(LiteralArgumentBuilder root) {
        // 1. /figura limit-test (OP / singleplayer check)
        root.then(LiteralArgumentBuilder.literal("limit-test")
            .requires(FiguraLimitCommand::isOp)
            .executes(context -> {
                Object source = context.getSource();
                if (!isOp(source)) {
                    sendFeedback(source, "You do not have permission to use this command.");
                    return 0;
                }

                if (checkConnected()) {
                    sendFeedback(source, "hello world (Figura backend connected)");
                } else {
                    sendFeedback(source, "Figura backend not connected");
                }
                return 1;
            })
        );

        // 2. /figura link-server (manual trigger to switch or check backend address)
        root.then(LiteralArgumentBuilder.literal("link-server")
            .executes(context -> {
                Object source = context.getSource();
                String current = FiguraLinkUtil.getServerIp();
                sendFeedback(source, "[FiguraLimit] Current Figura backend: " + (current != null ? current : "official"));
                return 1;
            })
            .then(RequiredArgumentBuilder.argument("address", StringArgumentType.greedyString())
                .executes(context -> {
                    Object source = context.getSource();
                    String address = StringArgumentType.getString(context, "address").trim();
                    if (address.matches("^\\d+$")) {
                        String host = FiguraClientSync.getRemoteServerHost();
                        if (host == null || host.isEmpty() || host.equals("127.0.0.1") || host.equals("localhost")) {
                            host = "127.0.0.1";
                        }
                        address = host + ":" + address;
                    }
                    FiguraClientSync.setFiguraServerIp(address);
                    sendFeedback(source, "[FiguraLimit] Switched Figura Cloud IP to: " + address);
                    return 1;
                })
            )
        );
    }

    private static boolean isOp(Object source) {
        if (source == null) return false;
        for (String mName : new String[]{"hasPermissionLevel", "method_9259"}) {
            try {
                Method m = source.getClass().getMethod(mName, int.class);
                return (Boolean) m.invoke(source, 2);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private static boolean checkConnected() {
        try {
            Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
            Method isConnected = ns.getMethod("isConnected");
            return (Boolean) isConnected.invoke(null);
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void sendFeedback(Object source, String message) {
        if (source == null) return;
        try {
            Class<?> textClass = null;
            try { textClass = Class.forName("net.minecraft.text.Text"); }
            catch (Throwable e) { textClass = Class.forName("net.minecraft.class_2561"); }

            Object text = null;
            for (Method m : textClass.getMethods()) {
                if ("literal".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    text = m.invoke(null, message);
                    break;
                }
            }
            if (text == null) {
                for (Method m : textClass.getMethods()) {
                    if ("of".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                        text = m.invoke(null, message);
                        break;
                    }
                }
            }

            if (text != null) {
                for (Method m : source.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("feedback")) {
                        if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class) {
                            if (m.getParameterTypes()[0].isAssignableFrom(text.getClass())) {
                                m.invoke(source, text, false);
                                return;
                            } else if (m.getParameterTypes()[0] == Supplier.class) {
                                final Object finalText = text;
                                Supplier<?> s = () -> finalText;
                                m.invoke(source, s, false);
                                return;
                            }
                        }
                    }
                }
                for (Method m : source.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("sendmessage")) {
                        if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class) {
                            if (m.getParameterTypes()[0].isAssignableFrom(text.getClass())) {
                                m.invoke(source, text, false);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
