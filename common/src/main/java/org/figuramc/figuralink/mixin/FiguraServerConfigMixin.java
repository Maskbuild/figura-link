package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(targets = "org.figuramc.figura.server.FiguraServerConfig", remap = false)
public class FiguraServerConfigMixin {

    @Inject(method = "getDefaultAvatarSizeLimit", at = @At("HEAD"), cancellable = true)
    private void onGetDefaultAvatarSizeLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(FiguraLinkConfig.getMaxAvatarSize());
    }

    @Inject(method = "avatarSizeLimit", at = @At("HEAD"), cancellable = true)
    private void onAvatarSizeLimit(Object server, UUID player, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(FiguraLinkConfig.getMaxAvatarSize());
    }
}
