package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.StageMovementPackets;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Connection.class)
public class ConnectionMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), argsOnly = true)
    private Packet<?> onSend(Packet<?> packet) {
        Connection connection = (Connection) (Object) this;
        ClientPacketListener handler = SongPlayer.MC.getConnection();
        if (handler != null && handler.getConnection() == connection) {
            packet = StageMovementPackets.rewriteAfterStageHandoff(connection, packet, SongHandler.getInstance().isIdle());
            StageMovementPackets.observeOutgoing(connection, packet);
        }
        return packet;
    }
}
