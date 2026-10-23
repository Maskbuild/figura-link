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

    @Inject(method = "tick", at = @At("HEAD"))
    private static void onTick(CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            try {
                Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
                Object cfg = configs.getField("ALLOW_UPLOADING_ERRORED_AVATARS").get(null);
                Field val = cfg.getClass().getField("value");
                if (Boolean.FALSE.equals(val.get(cfg))) {
                    val.set(cfg, true);
                }
            } catch (Throwable ignored) {}
        }
    }
}
