package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Used for debugging purposes

@Mixin(ClientLevel.class)
public class ClientWorldMixin {
    @Inject(at = @At("HEAD"), method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V", cancellable = true)
    public void onHandleBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        Stage stage = SongHandler.getInstance().stage;
        if (stage != null && !SongHandler.getInstance().building) {
            for (BlockPos nbp : stage.noteblockPositions.values()) {
                if (nbp.equals(pos)) {
                    BlockState oldState = SongPlayer.MC.level.getBlockState(pos);
                    if (oldState.equals(state))
                        return;
                    Util.showChatMessage(String.format("§7Block in stage changed from §2%s §7to §2%s", oldState.toString(), state.toString()));
                    break;
                }
            }
        }
    }
}
