package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;

@Mixin(targets = "org.figuramc.figura.backend2.HttpAPI", remap = false)
public class HttpAPIMixin {

    @Inject(method = "getUri", at = @At("HEAD"), cancellable = true)
    private static void onGetUri(String path, CallbackInfoReturnable<URI> cir) {
        if (FiguraLinkUtil.isCustomBackend()) {
            String address = FiguraLinkUtil.getServerIp();
            if (address != null && !address.isEmpty()) {
                String clean = address.replaceFirst("^(http://|https://)", "");
                String scheme = address.startsWith("https://") ? "https://" : "http://";
                String normPath = (path != null && path.startsWith("/")) ? path : ("/" + (path == null ? "" : path));
                cir.setReturnValue(URI.create(scheme + clean + "/api" + normPath));
            }
        }
    }
}
