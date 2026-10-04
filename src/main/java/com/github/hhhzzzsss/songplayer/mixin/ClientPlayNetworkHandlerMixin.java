package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.CommandProcessor;
import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import com.github.hhhzzzsss.songplayer.playing.StageMovementPackets;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(at = @At("HEAD"), method = "sendChat(Ljava/lang/String;)V", cancellable=true)
	private void onSendChatMessage(String content, CallbackInfo ci) {
		boolean isCommand = CommandProcessor.processChatMessage(content);
		if (isCommand) {
			ci.cancel();
		}
	}

	@Inject(at = @At("TAIL"), method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V")
	public void onOnGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
		SongHandler.getInstance().reset();
	}

	@Inject(at = @At("TAIL"), method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V")
	public void onOnPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		SongHandler.getInstance().reset();
	}

	@Inject(at = @At("TAIL"), method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V")
	public void onOnPlayerPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		// The acknowledgement and its vanilla position packet have already been sent.
		// Clear the guard before our stage recovery can emit a custom update.
		StageMovementPackets.endServerPositionCorrection(((ClientPacketListener) (Object) this).getConnection());
		Stage lastStage = SongHandler.getInstance().lastStage;
		LocalPlayer player = SongPlayer.MC.player;
		if (!SongHandler.getInstance().isIdle() && lastStage != null) {
			Vec3 stageOriginBottomCenter = lastStage.getOriginBottomCenter();
			boolean xrel = packet.relatives().contains(Relative.X);
			boolean yrel = packet.relatives().contains(Relative.Y);
			boolean zrel = packet.relatives().contains(Relative.Z);
			double dx;
			double dy;
			double dz;
			// Relative position sets need to be handled differently because client-side position doesn't match server-side position
			if (xrel) {
				dx = packet.change().position().x();
			} else {
				dx = player.getX() - stageOriginBottomCenter.x();
			}
			if (yrel) {
				dy = packet.change().position().y();
			} else {
				dy = player.getY() - stageOriginBottomCenter.y();
			}
			if (zrel) {
				dz = packet.change().position().z();
			} else {
				dz = player.getZ() - stageOriginBottomCenter.z();
			}
			double distsq = dx*dx + dy*dy + dz*dz;
			if (distsq > 3.0*3.0) {
				// Set client position to where server thinks player should be
				player.snapTo(
						xrel ? stageOriginBottomCenter.x() + dx : player.getX(),
						yrel ? stageOriginBottomCenter.y() + dy : player.getY(),
						zrel ? stageOriginBottomCenter.z() + dz : player.getZ(),
						player.getYRot(), player.getXRot()
				);
				Util.showChatMessage("§6Stopped playing/building because the server moved the player too far from the stage!");
				SongHandler.getInstance().restoreStateAndReset(false);
			} else {
				lastStage.movePlayerToStagePosition();
			}
		}
	}

	@Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void onBeginServerPositionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		// Begin only after thread scheduling; a HEAD injection would remain set when
		// the networking-thread invocation exits by throwing its scheduling exception.
		StageMovementPackets.beginServerPositionCorrection(((ClientPacketListener) (Object) this).getConnection());
	}

	@Inject(at = @At("TAIL"), method = "handlePlayerAbilities(Lnet/minecraft/network/protocol/game/ClientboundPlayerAbilitiesPacket;)V")
	public void onOnPlayerAbilities(ClientboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
		SongHandler handler = SongHandler.getInstance();
		if (!handler.isIdle()) {
			SongPlayer.MC.player.getAbilities().flying = handler.wasFlying;
		}
	}

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"), method = "handleSetEntityMotion", cancellable = true)
	public void onOnEntityVelocityUpdate(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
		if (!SongHandler.getInstance().isIdle() && packet.id() == SongPlayer.MC.player.getId()) {
			ci.cancel();
		}
	}
}
