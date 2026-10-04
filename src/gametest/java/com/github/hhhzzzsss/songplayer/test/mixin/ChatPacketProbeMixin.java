package com.github.hhhzzzsss.songplayer.test.mixin;

import com.github.hhhzzzsss.songplayer.test.ChatProbe;
import com.github.hhhzzzsss.songplayer.test.ViaMovementProbe;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ChatPacketProbeMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void songplayerTest$observeChat(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        ChatProbe.sent(packet);
        ViaMovementProbe.sent(packet);
    }
}
