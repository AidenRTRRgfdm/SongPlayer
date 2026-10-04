package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.Config;
import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonNetworkHandlerMixin {
    @Shadow
    @Final
    protected final Connection connection;

    public ClientCommonNetworkHandlerMixin() {
        connection = null;
    }

    @Inject(at = @At("HEAD"), method = "send(Lnet/minecraft/network/protocol/Packet;)V", cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        SongHandler songHandler = SongHandler.getInstance();
        Stage lastStage = songHandler.lastStage;

        if (!songHandler.isIdle() && packet instanceof ServerboundMovePlayerPacket) {
            if (lastStage != null) {
                if (!Config.getConfig().rotate) { // Only copy player rotation if rotate is not enabled
                    connection.send(new ServerboundMovePlayerPacket.PosRot(
                            lastStage.position.getX() + 0.5, lastStage.position.getY(), lastStage.position.getZ() + 0.5,
                            SongPlayer.MC.player.getYRot(), SongPlayer.MC.player.getXRot(),
                            true, false));
                    if (songHandler.fakePlayer != null) {
                        songHandler.fakePlayer.copyStagePosAndPlayerLook();
                    }
                }
            }
            ci.cancel(); // Default movement packet is always cancelled if song is playing
        }
        else if (packet instanceof ServerboundPlayerInputPacket) {
            // Update fakePlayer crouching
            Input input = ((ServerboundPlayerInputPacket) packet).input();
            if (songHandler.fakePlayer != null) {
                if (input.shift()) {
                    songHandler.fakePlayer.setShiftKeyDown(true);
                    songHandler.fakePlayer.setPose(Pose.CROUCHING);
                }
                else {
                    songHandler.fakePlayer.setShiftKeyDown(false);
                    songHandler.fakePlayer.setPose(Pose.STANDING);
                }
            }
        }
    }
}
