package com.github.hhhzzzsss.songplayer.test.mixin;

import com.github.hhhzzzsss.songplayer.test.BlockEventProbe;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class NoteEventProbeMixin {
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void songplayerTest$observeNote(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        BlockEventProbe.record(packet);
    }
}
