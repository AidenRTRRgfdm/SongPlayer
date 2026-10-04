package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.playing.ProgressDisplay;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class InGameHudMixin {
    @Shadow
    private int toolHighlightTimer;

    @Inject(at = @At("TAIL"), method = "extractSelectedItemName(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V")
    private void onRenderHeldItemTooltip(GuiGraphicsExtractor context, CallbackInfo ci) {
        ProgressDisplay.getInstance().onRenderHUD(context, toolHighlightTimer);
    }
}
