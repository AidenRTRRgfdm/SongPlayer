package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.item.SongItemConfirmationScreen;
import com.github.hhhzzzsss.songplayer.item.SongItemUtils;
import com.github.hhhzzzsss.songplayer.playing.ProgressDisplay;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
	@Shadow
	public HitResult hitResult;

	@Shadow
	private int rightClickDelay;

	@Inject(at = @At("HEAD"), method = "runTick(Z)V")
	public void onRender(boolean tick, CallbackInfo ci) {
		if (SongPlayer.MC.level != null && SongPlayer.MC.player != null && SongPlayer.MC.gameMode != null) {
			SongHandler.getInstance().onUpdate(false);
		} else {
			SongHandler.getInstance().onNotIngame();
		}
	}

	@Inject(at = @At("HEAD"), method = "tick()V")
	public void onTick(CallbackInfo ci) {
		if (SongPlayer.MC.level != null && SongPlayer.MC.player != null && SongPlayer.MC.gameMode != null) {
			SongHandler.getInstance().onUpdate(true);
		}
		ProgressDisplay.getInstance().onTick();
	}

	@Inject(at = @At("HEAD"), method = "startUseItem()V", cancellable = true)
	private void onDoItemUse(CallbackInfo ci) {
		if (hitResult != null) {
			if (hitResult.getType() == HitResult.Type.ENTITY) {
				EntityHitResult entityHitResult = (EntityHitResult)this.hitResult;
				Entity entity = entityHitResult.getEntity();
				if (entity instanceof ItemFrame || entity instanceof GlowItemFrame) {
					return;
				}
			}
			else if (hitResult.getType() == HitResult.Type.BLOCK) {
				BlockHitResult blockHitResult = (BlockHitResult)this.hitResult;
				BlockEntity blockEntity = SongPlayer.MC.level.getBlockEntity(blockHitResult.getBlockPos());
				if (blockEntity != null && blockEntity instanceof BaseContainerBlockEntity) {
					return;
				}
			}
		}

		ItemStack stack = SongPlayer.MC.player.getItemInHand(InteractionHand.MAIN_HAND);
		if (SongItemUtils.isSongItem(stack)) {
			try {
				SongPlayer.MC.gui.setScreen(new SongItemConfirmationScreen(stack));
			} catch (Exception e) {
				Util.showChatMessage("§cFailed to load song item: §4" + e.getMessage());
			}
			rightClickDelay = 4;
			ci.cancel();
		}
	}
}
