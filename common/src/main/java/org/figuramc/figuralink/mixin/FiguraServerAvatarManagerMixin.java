package org.figuramc.figuralink.mixin;

import org.figuramc.figura.server.FiguraServer;
import org.figuramc.figura.server.FiguraUser;
import org.figuramc.figura.server.avatars.FiguraServerAvatarManager;
import org.figuramc.figura.server.packets.CloseIncomingStreamPacket;
import org.figuramc.figura.server.utils.StatusCode;
import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FiguraServerAvatarManager.class, remap = false)
public class FiguraServerAvatarManagerMixin {

    @Shadow
    @Final
    private FiguraServer parent;

    @Inject(method = "receiveAvatar", at = @At("HEAD"), cancellable = true)
    private void onReceiveAvatar(FiguraUser user, String avatarId, int streamId, byte[] hash, byte[] ehash, CallbackInfo ci) {
        if (FiguraLinkConfig.isTotalStorageExceeded(this.parent.getAvatarsFolder())) {
            FiguraLink.LOGGER.warn("[FiguraLimit] Avatar upload from {} rejected: Total server storage limit reached ({} bytes)",
                    user.name(), FiguraLinkConfig.getTotalStorageLimit());
            user.sendPacket(new CloseIncomingStreamPacket(streamId, StatusCode.TOO_MANY_AVATARS));
            ci.cancel();
        }
    }
}
