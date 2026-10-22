package org.figuramc.figuralink.mixin;

import org.figuramc.figura.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.figuramc.figura.backend2.websocket.FiguraWebSocketAdapter", remap = false)
public class FiguraWebSocketAdapterMixin {

    @Inject(method = "getBackendAddress", at = @At("HEAD"), cancellable = true)
    private static void onGetBackendAddress(CallbackInfoReturnable<String> cir) {
        String address = Configs.SERVER_IP.value;
        if (address != null && !address.isEmpty() && !address.equalsIgnoreCase("figura.moonlight-devs.org")) {
            String clean = address.replaceFirst("^(http://|https://|ws://|wss://)", "");
            cir.setReturnValue("ws://" + clean + "/ws");
        }
    }
}
