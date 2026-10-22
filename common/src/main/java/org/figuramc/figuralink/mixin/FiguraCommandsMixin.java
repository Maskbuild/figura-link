package org.figuramc.figuralink.mixin;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figuralink.command.FiguraLimitCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.figuramc.figura.commands.FiguraCommands", remap = false)
public class FiguraCommandsMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getCommandRoot", at = @At("RETURN"), cancellable = true)
    private static void onGetCommandRoot(CallbackInfoReturnable<LiteralArgumentBuilder> cir) {
        LiteralArgumentBuilder root = cir.getReturnValue();
        if (root != null) {
            FiguraLimitCommand.registerSubcommand(root);
        }
    }
}
