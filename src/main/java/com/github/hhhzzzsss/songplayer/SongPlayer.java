package com.github.hhhzzzsss.songplayer;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.Path;

public class SongPlayer implements ModInitializer {
	public static final Minecraft MC = Minecraft.getInstance();

	public static final Path SONG_DIR = Path.of("songs");
	public static final Path SONGPLAYER_DIR = Path.of("SongPlayer");
	public static final Path PLAYLISTS_DIR = Path.of("SongPlayer/playlists");

	@Override
	public void onInitialize() {
		if (!Files.exists(SONG_DIR)) {
			Util.createDirectoriesSilently(SONG_DIR);
		}
		if (!Files.exists(SONGPLAYER_DIR)) {
			Util.createDirectoriesSilently(SONGPLAYER_DIR);
		}
		if (!Files.exists(PLAYLISTS_DIR)) {
			Util.createDirectoriesSilently(PLAYLISTS_DIR);
		}

		CommandProcessor.initCommands();
	}
}
