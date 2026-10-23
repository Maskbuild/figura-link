package org.figuramc.figuralink.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.command.FiguraLimitCommand;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FiguraLinkFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FiguraLink.init();
        registerServerCommand();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerServerCommand() {
        try {
            Class<?> cbClass = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            Object event = cbClass.getField("EVENT").get(null);

            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            Method registerMethod = null;
            for (Method m : eventClass.getMethods()) {
                if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                    registerMethod = m;
                    break;
                }
            }

            if (registerMethod != null) {
                Object handler = Proxy.newProxyInstance(
                    cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        if ("register".equals(method.getName()) && args != null && args.length >= 1) {
                            CommandDispatcher dispatcher = (CommandDispatcher) args[0];
                            LiteralArgumentBuilder root = LiteralArgumentBuilder.literal("figura");
                            FiguraLimitCommand.registerSubcommand(root);
                            dispatcher.register(root);
                            FiguraLink.LOGGER.info("[FiguraLimit] Registered server command '/figura'");
                        }
                        return null;
                    }
                );
                registerMethod.invoke(event, handler);
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Server command registration error: " + t.getMessage());
        }
    }
}
