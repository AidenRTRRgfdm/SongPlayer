package com.github.hhhzzzsss.songplayer;

import com.github.hhhzzzsss.songplayer.mixin.ClientPlayNetworkHandlerAccessor;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Pose;

public class FakePlayerEntity extends RemotePlayer {
	public static final UUID FAKE_PLAYER_UUID = UUID.randomUUID();

	LocalPlayer player = SongPlayer.MC.player;
	ClientLevel world = SongPlayer.MC.level;

	public FakePlayerEntity() {
		super(SongPlayer.MC.level, createProfile());
		setId(allocateClientEntityId(world));

		copyStagePosAndPlayerLook();

		getInventory().replaceWith(player.getInventory());

		Byte playerModel = player.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION);
		getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, playerModel);

		yHeadRot = player.yHeadRot;
		yBodyRot = player.yBodyRot;

		if (player.isShiftKeyDown()) {
			setShiftKeyDown(true);
			setPose(Pose.CROUCHING);
		}

//		capeX = getX();
//		capeY = getY();
//		capeZ = getZ();

		world.addEntity(this);
	}

	private static int allocateClientEntityId(ClientLevel world) {
		// Client-created entities no longer receive an ID from their constructor.
		// Use an unused negative ID to avoid replacing a server entity or another mod's fake player.
		for (int id = -1; id < 0; id--) {
			if (world.getEntity(id) == null) {
				return id;
			}
		}
		throw new IllegalStateException("No client-only entity IDs available");
	}

	public void resetPlayerPosition() {
		player.snapTo(getX(), getY(), getZ(), getYRot(), getXRot());
	}

	public void copyStagePosAndPlayerLook() {
		Stage lastStage = SongHandler.getInstance().lastStage;
		if (lastStage != null) {
			snapTo(lastStage.position.getX()+0.5, lastStage.position.getY(), lastStage.position.getZ()+0.5, player.getYRot(), player.getXRot());
			yHeadRot = player.yHeadRot;
		}
		else {
			copyPosition(player);
		}
	}

	private static GameProfile createProfile() {
		GameProfile profile = new GameProfile(
				FAKE_PLAYER_UUID,
				SongPlayer.MC.player.getGameProfile().name(),
				SongPlayer.MC.getGameProfile().properties()
		);
		PlayerInfo playerListEntry = new PlayerInfo(SongPlayer.MC.player.getGameProfile(), false);
		((ClientPlayNetworkHandlerAccessor)SongPlayer.MC.getConnection()).getPlayerInfoMap().put(FAKE_PLAYER_UUID, playerListEntry);
		return profile;
	}
}
