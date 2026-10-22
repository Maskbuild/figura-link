package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.util.FiguraLinkUtil;
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

    @Inject(method = "canUpload", at = @At("HEAD"), cancellable = true)
    private static void onCanUpload(CallbackInfoReturnable<Boolean> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isConnected", at = @At("HEAD"), cancellable = true)
    private static void onIsConnected(CallbackInfoReturnable<Boolean> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkWS", at = @At("HEAD"), cancellable = true)
    private static void onCheckWS(CallbackInfoReturnable<Boolean> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private static void onDisconnect(String reason, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private static void onTick(CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            try {
                Class<?> ns = Class.forName("org.figuramc.figura.backend2.NetworkStuff");
                Field statusField = ns.getDeclaredField("backendStatus");
                statusField.setAccessible(true);
                statusField.setInt(null, 3);

                Field reasonField = ns.getDeclaredField("disconnectedReason");
                reasonField.setAccessible(true);
                reasonField.set(null, null);

                Field uploadRateField = ns.getDeclaredField("uploadRate");
                uploadRateField.setAccessible(true);
                Object uploadRate = uploadRateField.get(null);
                if (uploadRate != null) {
                    java.lang.reflect.Method setMethod = uploadRate.getClass().getMethod("set", double.class);
                    setMethod.invoke(uploadRate, 50.0);
                }

                Field apiField = ns.getDeclaredField("api");
                apiField.setAccessible(true);
                if (apiField.get(null) == null) {
                    java.lang.reflect.Method connectAPI = ns.getDeclaredMethod("connectAPI", String.class);
                    connectAPI.setAccessible(true);
                    connectAPI.invoke(null, "figuralink-token");
                }

                try {
                    Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                    Field lu = am.getDeclaredField("localUploaded");
                    lu.setAccessible(true);
                    lu.setBoolean(null, false);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
    }

    @Inject(method = "connectWS", at = @At("HEAD"), cancellable = true)
    private static void onConnectWS(String token, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
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
