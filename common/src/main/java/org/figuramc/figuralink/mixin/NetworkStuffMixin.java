package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "checkUUID", at = @At("HEAD"), cancellable = true)
    private static void onCheckUUID(UUID uuid, CallbackInfoReturnable<Boolean> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private static void onDisconnect(String reason, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "subscribe", at = @At("HEAD"), cancellable = true)
    private static void onSubscribe(UUID uuid, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "unsubscribe", at = @At("HEAD"), cancellable = true)
    private static void onUnsubscribe(UUID uuid, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "async", at = @At("HEAD"), cancellable = true)
    private static void onAsync(Runnable runnable, CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            ci.cancel();
            if (runnable != null) {
                CompletableFuture.runAsync(runnable);
            }
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
                    Method setMethod = uploadRate.getClass().getMethod("set", double.class);
                    setMethod.invoke(uploadRate, 50.0);
                }

                // Ensure WS_REQUESTS queue is cleared to avoid NPE
                try {
                    Field wsRequests = ns.getDeclaredField("WS_REQUESTS");
                    wsRequests.setAccessible(true);
                    Object q = wsRequests.get(null);
                    if (q instanceof java.util.Collection) {
                        ((java.util.Collection<?>) q).clear();
                    }
                } catch (Throwable ignored) {}

                // Allow uploading errored avatars (e.g. missing texture files in blockbench model)
                try {
                    Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
                    Object cfg = configs.getField("ALLOW_UPLOADING_ERRORED_AVATARS").get(null);
                    Field val = cfg.getClass().getField("value");
                    if (Boolean.FALSE.equals(val.get(cfg))) {
                        val.set(cfg, true);
                    }
                } catch (Throwable ignored) {}

                Field apiField = ns.getDeclaredField("api");
                apiField.setAccessible(true);
                if (apiField.get(null) == null) {
                    String playerUuid = "default";
                    try {
                        Class<?> fm = Class.forName("org.figuramc.figura.FiguraMod");
                        Object u = fm.getMethod("getLocalPlayerUUID").invoke(null);
                        if (u != null) playerUuid = u.toString();
                    } catch (Throwable ignored) {}
                    Method connectAPI = ns.getDeclaredMethod("connectAPI", String.class);
                    connectAPI.setAccessible(true);
                    connectAPI.invoke(null, playerUuid);
                }
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

    @Inject(method = "equipAvatar", at = @At("RETURN"))
    private static void onEquipAvatar(CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            try {
                Class<?> fm = Class.forName("org.figuramc.figura.FiguraMod");
                Object localUuid = fm.getMethod("getLocalPlayerUUID").invoke(null);
                if (localUuid instanceof UUID) {
                    Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                    try {
                        Field lu = am.getDeclaredField("localUploaded");
                        lu.setAccessible(true);
                        lu.setBoolean(null, true);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }
}
