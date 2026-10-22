package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

@Mixin(targets = "org.figuramc.figura.backend2.NetworkStuff", remap = false)
public class NetworkStuffMixin {

    @Inject(method = "getSizeLimit", at = @At("RETURN"), cancellable = true)
    private static void onGetSizeLimit(CallbackInfoReturnable<Integer> cir) {
        int configLimit = FiguraLinkConfig.getMaxAvatarSize();
        if (configLimit > 0) {
            cir.setReturnValue(configLimit);
        }
    }

    @Inject(method = "isConnected", at = @At("HEAD"), cancellable = true)
    private static void onIsConnected(CallbackInfoReturnable<Boolean> cir) {
        if (HttpAPIMixin.isCustomBackend()) {
            try {
                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                Field apiField = ns.getDeclaredField("api");
                apiField.setAccessible(true);
                Object api = apiField.get(null);
                cir.setReturnValue(api != null);
            } catch (Throwable t) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "checkWS", at = @At("HEAD"), cancellable = true)
    private static void onCheckWS(CallbackInfoReturnable<Boolean> cir) {
        if (HttpAPIMixin.isCustomBackend()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "connectWS", at = @At("HEAD"), cancellable = true)
    private static void onConnectWS(String token, CallbackInfo ci) {
        if (HttpAPIMixin.isCustomBackend()) {
            try {
                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                Field statusField = ns.getDeclaredField("backendStatus");
                statusField.setAccessible(true);
                statusField.setInt(null, 3);
            } catch (Throwable ignored) {}
            ci.cancel();
        }
    }
}
