package com.github.hhhzzzsss.songplayer.test.mixin;

import com.github.hhhzzzsss.songplayer.test.ChatProbe;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatOutputProbeMixin {
    @Inject(method = "addClientSystemMessage", at = @At("HEAD"))
    private void songplayerTest$observeOutput(Component message, CallbackInfo ci) { ChatProbe.output(message); }
}
