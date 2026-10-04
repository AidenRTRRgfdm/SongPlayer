package com.github.hhhzzzsss.songplayer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

@Mixin(ClientPacketListener.class)
public interface ClientPlayNetworkHandlerAccessor {
	@Accessor
	Map<UUID, PlayerInfo> getPlayerInfoMap();
}
