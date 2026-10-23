package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(targets = "org.figuramc.figura.utils.EntityUtils", remap = false)
public class EntityUtilsMixin {

    @Inject(method = "checkInvalidPlayer", at = @At("HEAD"), cancellable = true)
    private static void onCheckInvalidPlayer(UUID uuid, CallbackInfoReturnable<Boolean> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            // Never mark any player as invalid on custom FiguraLink server (supports all UUID versions)
            cir.setReturnValue(false);
        }
    }
}
