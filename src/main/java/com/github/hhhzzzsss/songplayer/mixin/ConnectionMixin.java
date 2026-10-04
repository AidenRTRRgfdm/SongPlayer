package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.playing.StageMovementPackets;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        Connection connection = (Connection) (Object) this;
        ClientPacketListener handler = SongPlayer.MC.getConnection();
        if (handler != null && handler.getConnection() == connection) {
            StageMovementPackets.observeOutgoing(connection, packet);
        }
    }
}
