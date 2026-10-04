package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.Config;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public class PlayerEntityMixin {
    @Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z"))
    private void redirectNoClip(Player instance, boolean value) {
        if (Config.getConfig().flightNoclip && !SongHandler.getInstance().isIdle())
            instance.noPhysics = instance.getAbilities().flying;
        else
            instance.noPhysics = value;
    }
}
