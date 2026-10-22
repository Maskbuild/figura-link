package org.figuramc.figuralink.mixin;

import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NetworkStuff.class, remap = false)
public class NetworkStuffMixin {

    @Inject(method = "maxAvatarSize", at = @At("RETURN"), cancellable = true)
    private static void onMaxAvatarSize(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        // If server allows larger or local config allows larger, return the higher limit
        int configLimit = FiguraLinkConfig.getMaxAvatarSize();
        if (original < configLimit && original > 0) {
            cir.setReturnValue(configLimit);
        }
    }
}
