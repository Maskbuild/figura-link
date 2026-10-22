package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.nio.file.Paths;

@Mixin(targets = "org.figuramc.figura.server.avatars.FiguraServerAvatarManager", remap = false)
public class FiguraServerAvatarManagerMixin {

    @Inject(method = "receiveAvatar", at = @At("HEAD"), cancellable = true)
    private void onReceiveAvatar(Object user, String avatarId, int streamId, byte[] hash, byte[] ehash, CallbackInfo ci) {
        Path avatarsFolder = Paths.get("figura/avatars");
        if (FiguraLinkConfig.isTotalStorageExceeded(avatarsFolder)) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Avatar upload rejected: Total server storage limit reached ({} bytes)",
                    FiguraLinkConfig.getTotalStorageLimit());
            ci.cancel();
        }
    }
}
