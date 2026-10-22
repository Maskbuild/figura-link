package org.figuramc.figuralink.mixin;

import org.figuramc.figura.server.FiguraServer;
import org.figuramc.figura.server.FiguraServerConfig;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = FiguraServerConfig.class, remap = false)
public class FiguraServerConfigMixin {

    @Inject(method = "getDefaultAvatarSizeLimit", at = @At("HEAD"), cancellable = true)
    private void onGetDefaultAvatarSizeLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(FiguraLinkConfig.getMaxAvatarSize());
    }

    @Inject(method = "avatarSizeLimit", at = @At("HEAD"), cancellable = true)
    private void onAvatarSizeLimit(FiguraServer server, UUID player, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(FiguraLinkConfig.getMaxAvatarSize());
    }
}
