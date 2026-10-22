package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.figuramc.figura.backend2.AuthHandler", remap = false)
public class AuthHandlerMixin {

    @Inject(method = "auth", at = @At("HEAD"), cancellable = true)
    private static void onAuth(boolean force, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
            try {
                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                Method asyncMethod = ns.getMethod("async", Runnable.class);
                asyncMethod.invoke(null, (Runnable) () -> {
                    try {
                        Field statusField = ns.getDeclaredField("backendStatus");
                        statusField.setAccessible(true);
                        statusField.setInt(null, 3);

                        Method authSuccess = ns.getDeclaredMethod("authSuccess", String.class);
                        authSuccess.setAccessible(true);
                        authSuccess.invoke(null, "figuralink-token");

                        statusField.setInt(null, 3);
                        FiguraLink.LOGGER.info("[FiguraLimit] Custom backend authenticated successfully (Status: GREEN).");
                    } catch (Throwable t) {
                        FiguraLink.LOGGER.error("[FiguraLimit] Error in custom auth handler", t);
                    }
                });
            } catch (Throwable t) {
                FiguraLink.LOGGER.error("[FiguraLimit] Failed to invoke NetworkStuff.async", t);
            }
        }
    }
}
