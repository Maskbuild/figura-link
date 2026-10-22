package org.figuramc.figuralink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.figuramc.figura.backend2.websocket.FiguraWebSocketAdapter", remap = false)
public class FiguraWebSocketAdapterMixin {

    @Inject(method = "getBackendAddress", at = @At("HEAD"), cancellable = true)
    private static void onGetBackendAddress(CallbackInfoReturnable<String> cir) {
        if (HttpAPIMixin.isCustomBackend()) {
            String address = HttpAPIMixin.getServerIp();
            if (address != null && !address.isEmpty()) {
                String clean = address.replaceFirst("^(http://|https://|ws://|wss://)", "");
                cir.setReturnValue("ws://" + clean + "/ws");
            }
        }
    }

    @Inject(method = "handleClose", at = @At("HEAD"), cancellable = true)
    private void onHandleClose(int code, String reason, CallbackInfo ci) {
        if (HttpAPIMixin.isCustomBackend()) {
            ci.cancel();
        }
    }
}
