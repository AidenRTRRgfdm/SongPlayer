package com.github.hhhzzzsss.songplayer.playing;

import com.github.hhhzzzsss.songplayer.Config;
import com.github.hhhzzzsss.songplayer.FakePlayerEntity;
import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.mixin.ClientPlayerInteractionManagerAccessor;
import com.github.hhhzzzsss.songplayer.song.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SongHandler {
    private static SongHandler instance = null;
    public static SongHandler getInstance() {
        if (instance == null) {
            instance = new SongHandler();
        }
        return instance;
    }
    private SongHandler() {}

    public SongLoaderThread loaderThread = null;
    public LinkedList<Song> songQueue = new LinkedList<>();
    public Song currentSong = null;
    public Playlist currentPlaylist = null;
    public Stage stage = null; // Only exists when playing
    public Stage lastStage = null; // Stays around even after playing
    public FakePlayerEntity fakePlayer;
    public HashMap<BlockPos, BlockState> originalBlocks = new HashMap<>();
    public boolean building = false;
    public boolean cleaningUp = false;
    public boolean dirty = false;

    public boolean wasFlying = false;
    public GameType originalGamemode = GameType.CREATIVE;

    boolean playlistChecked = false;

    public void onUpdate(boolean tick) {
        if (!cleaningUp) {
            // Check current playlist and load song from it if necessary
            if (currentSong == null && currentPlaylist != null && currentPlaylist.loaded) {
                if (!playlistChecked) {
                    playlistChecked = true;
                    if (currentPlaylist.songsFailedToLoad.size() > 0) {
                        Util.showChatMessage("§cFailed to load the following songs from the playlist: §4" + String.join(" ", currentPlaylist.songsFailedToLoad));
                    }
                }
                Song nextSong = currentPlaylist.getNext();
                if (currentPlaylist.songs.size() == 0) {
                    Util.showChatMessage("§cPlaylist has no playable songs");
                    currentPlaylist = null;
                } else if (nextSong == null) {
                    Util.showChatMessage("§6Playlist has finished playing");
                    currentPlaylist = null;
                } else {
                    nextSong.reset();
                    setSong(nextSong);
                }
            }

            // Check queue and load song from it if necessary
            if (currentSong == null && currentPlaylist == null && songQueue.size() > 0) {
                setSong(songQueue.poll());
            }

            // Check if loader thread is finished and handle accordingly
            if (loaderThread != null && !loaderThread.isAlive()) {
                if (loaderThread.exception != null) {
                    Util.showChatMessage("§cFailed to load song: §4" + loaderThread.exception.getMessage());
                } else {
                    if (currentSong == null) {
                        setSong(loaderThread.song);
                    } else {
                        queueSong(loaderThread.song);
                    }
                }
                loaderThread = null;
            }
        }

        // Run cached command if timeout reached
        checkCommandCache();

        // If either playing or doing cleanup
        if (cleaningUp || currentSong != null) {
            // Handle creating/removing fake player depending on settings
            if (Config.getConfig().showFakePlayer && fakePlayer == null) {
                fakePlayer = new FakePlayerEntity();
                fakePlayer.copyStagePosAndPlayerLook();
            }
            if (!Config.getConfig().showFakePlayer && fakePlayer != null) {
                removeFakePlayer();
            }
            if (fakePlayer != null) {
                fakePlayer.getInventory().replaceWith(SongPlayer.MC.player.getInventory());
            }

            // Maintain flying status
            wasFlying = SongPlayer.MC.player.getAbilities().flying;
        }

        // Check if doing cleanup
        if (cleaningUp) {
            if (tick) {
                // Maintain flying status
                wasFlying = SongPlayer.MC.player.getAbilities().flying;

                handleCleanup();
            }
        }
        // Check if song is playing
        else if (currentSong != null) {
            // This should never happen, but I left this check in just in case.
            if (stage == null) {
                Util.showChatMessage("§cStage is null! This should not happen!");
                reset();
                return;
            }

            // Run building or playing tick depending on state
            if (building) {
                if (tick) {
                    handleBuilding();
                }
            } else {
                handlePlaying(tick);
            }
        }
        // Otherwise, handle cleanup if necessary
        else {
            if (dirty) {
                if (Config.getConfig().autoCleanup && originalBlocks.size() != 0 && !Config.getConfig().survivalOnly) {
                    partialResetAndCleanup();
                } else {
                    restoreStateAndReset();
                }
            }
            else {
                // When doing nothing else, record original gamemode
                originalGamemode = SongPlayer.MC.gameMode.getPlayerMode();
            }
        }
    }

    public void loadSong(String location) {
        if (loaderThread != null) {
            Util.showChatMessage("§cAlready loading a song, cannot load another");
        }
        else if (currentPlaylist != null) {
            Util.showChatMessage("§cCannot load a song while a playlist is playing");
        }
        else {
            try {
                loaderThread = new SongLoaderThread(location);
                Util.showChatMessage("§6Loading §3" + location);
                loaderThread.start();
            } catch (IOException e) {
                Util.showChatMessage("§cFailed to load song: §4" + e.getMessage());
            }
        }
    }

    public void loadSong(SongLoaderThread thread) {
        if (loaderThread != null) {
            Util.showChatMessage("§cAlready loading a song, cannot load another");
        }
        else if (currentPlaylist != null) {
            Util.showChatMessage("§cCannot load a song while a playlist is playing");
        }
        else {
            loaderThread = thread;
        }
    }

    // Sets currentSong and sets everything up for building
    public void setSong(Song song) {
        dirty = true;
        currentSong = song;
        building = true;
        if (!Config.getConfig().survivalOnly) setCreativeIfNeeded();
        if (Config.getConfig().doAnnouncement) {
            sendMessage(Config.getConfig().announcementMessage.replaceAll("\\[name\\]", song.name));
        }
        if (!Config.getConfig().survivalOnly) getAndSaveBuildSlot();
        prepareStage();
        Util.showChatMessage("§6Building noteblocks");
    }

    private void queueSong(Song song) {
        songQueue.add(song);
        Util.showChatMessage("§6Added song to queue: §3" + song.name);
    }

    public void setPlaylist(Path playlist) {
        if (loaderThread != null || currentSong != null || !songQueue.isEmpty()) {
            Util.showChatMessage("§cCannot start playing a playlist while something else is playing");
        }
        else {
            currentPlaylist = new Playlist(playlist, Config.getConfig().loopPlaylists, Config.getConfig().shufflePlaylists);
            playlistChecked = false;
        }
    }

    public void setPlaylistLoop(boolean loop) {
        if (currentPlaylist != null) {
            currentPlaylist.setLoop(loop);
        }
    }

    public void setPlaylistShuffle(boolean shuffle) {
        if (currentPlaylist != null) {
            currentPlaylist.setShuffle(shuffle);
        }
    }

    public void startCleanup() {
        dirty = true;
        cleaningUp = true;
        lastCleanupHash = 0;
        setCreativeIfNeeded();
        getAndSaveBuildSlot();
        lastStage.sendMovementPacketToStagePosition();
    }

    // Runs every tick
    private int buildStartDelay = 0;
    private int buildEndDelay = 0;
    private int buildSlot = -1;
    private ItemStack prevHeldItem = null;
    private void handleBuilding() {
        setBuildProgressDisplay();
        if (buildStartDelay > 0) {
            buildStartDelay--;
            return;
        }
        ClientLevel world = SongPlayer.MC.level;
        if (!Config.getConfig().survivalOnly && SongPlayer.MC.gameMode.getPlayerMode() != GameType.CREATIVE) {
            return;
        }

        if (stage.nothingToBuild()) { // If there's nothing to build, wait for end delay then check build status
            if (buildEndDelay > 0) { // Wait for end delay
                buildEndDelay--;
                return;
            } else { // Check build status when end delay is over
                if (!Config.getConfig().survivalOnly) {
                    stage.checkBuildStatus(currentSong);
                    recordStageBlocks();
                } else {
                    try {
                        stage.checkSurvivalBuildStatus(currentSong);
                    } catch (Stage.NotEnoughInstrumentsException e) {
                        e.giveInstrumentSummary();
                        restoreStateAndReset();
                        return;
                    }
                }
                stage.sendMovementPacketToStagePosition();
            }
        }

        if (stage.nothingToBuild()) { // If there's still nothing to build after checking build status, switch to playing
            building = false;
            if (!Config.getConfig().survivalOnly) {
                setSurvivalIfNeeded();
                restoreBuildSlot();
            }
            stage.sendMovementPacketToStagePosition();
            Util.showChatMessage("§6Now playing §3" + currentSong.name);
        }

        if (!Config.getConfig().survivalOnly) { // Regular mode
            if (!stage.requiredBreaks.isEmpty()) {
                incrementBreakAllowance();
                while (consumeBreakAllowance()) {
                    if (stage.requiredBreaks.isEmpty()) continue;
                    BlockPos bp = stage.requiredBreaks.poll();
                    attackBlock(bp);
                }
                buildEndDelay = 20;
            } else if (!stage.missingNotes.isEmpty()) {
                incrementPlaceAllowance();
                while (consumePlaceAllowance()) {
                    if (stage.missingNotes.isEmpty()) continue;
                    int desiredNoteId = stage.missingNotes.pollFirst();
                    BlockPos bp = stage.noteblockPositions.get(desiredNoteId);
                    if (bp == null) {
                        return;
                    }
                    BlockState existingState = world.getBlockState(bp);
                    int currentNoteId = NoteBlockNotes.getNoteId(existingState);
                    if (currentNoteId != desiredNoteId) {
                        holdNoteblock(desiredNoteId, buildSlot);
                        if (!existingState.isAir()) {
                            attackBlock(bp);
                        }
                        placeBlock(bp);
                    }
                }
                buildEndDelay = 20;
            }
        } else { // Survival only mode
            if (!stage.requiredClicks.isEmpty()) {
                BlockPos bp = stage.requiredClicks.pollFirst();
                if (SongPlayer.MC.level.getBlockState(bp).getBlock() == Blocks.NOTE_BLOCK) {
                    placeBlock(bp);
                }
                buildEndDelay = 20;
            }
        }
    }
    private void setBuildProgressDisplay() {
        MutableComponent buildText = Component.empty()
                .append(Component.literal("Building noteblocks | " ).withStyle(ChatFormatting.GOLD))
                .append(Component.literal((stage.totalMissingNotes - stage.missingNotes.size()) + "/" + stage.totalMissingNotes).withStyle(ChatFormatting.DARK_AQUA));
        MutableComponent playlistText = Component.empty();
        if (currentPlaylist != null && currentPlaylist.loaded) {
            playlistText = playlistText.append(Component.literal("Playlist: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(currentPlaylist.name).withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.format(" (%s/%s)", currentPlaylist.songNumber, currentPlaylist.songs.size())).withStyle(ChatFormatting.DARK_AQUA));
            if (currentPlaylist.loop) {
                playlistText.append(Component.literal(" | Looping").withStyle(ChatFormatting.GOLD));
            }
            if (currentPlaylist.shuffle) {
                playlistText.append(Component.literal(" | Shuffled").withStyle(ChatFormatting.GOLD));
            }
        }
        ProgressDisplay.getInstance().setText(buildText, playlistText);
    }

    // Runs every frame
    private void handlePlaying(boolean tick) {
        if (tick) {
            setPlayProgressDisplay();
        }

        if (SongPlayer.MC.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            currentSong.pause();
            return;
        }

        if (tick) {
            if (stage.hasBreakingModification()) {
                if (!Config.getConfig().survivalOnly) {
                    stage.checkBuildStatus(currentSong);
                    recordStageBlocks();
                } else {
                    try {
                        stage.checkSurvivalBuildStatus(currentSong);
                    } catch (Stage.NotEnoughInstrumentsException e) {
                        Util.showChatMessage("§6Stopped because stage is missing instruments required for song.");
                        restoreStateAndReset();
                        return;
                    }
                }
            }
            if (!stage.nothingToBuild()) { // Switch to building
                building = true;
                if (!Config.getConfig().survivalOnly) setCreativeIfNeeded();
                stage.sendMovementPacketToStagePosition();
                currentSong.pause();
                buildStartDelay = 20;
                System.out.println("Total missing notes: " + stage.missingNotes.size());
                for (int note : stage.missingNotes) {
                    int pitch = note % 25;
                    int instrumentId = note / 25;
                    System.out.println("Missing note: " + Instrument.getInstrumentFromId(instrumentId).name() + ":" + pitch);
                }
                if (!Config.getConfig().survivalOnly) getAndSaveBuildSlot();
                Util.showChatMessage("§6Stage was altered. Rebuilding!");
                return;
            }
        }

        currentSong.play();

        boolean somethingPlayed = false;
        currentSong.advanceTime();
        while (currentSong.reachedNextNote()) {
            Note note = currentSong.getNextNote();
            if (note.velocity >= Config.getConfig().velocityThreshold) {
                BlockPos bp = stage.noteblockPositions.get(note.noteId);
                if (bp != null) {
                    attackBlock(bp);
                    somethingPlayed = true;
                }
            }
        }
        if (somethingPlayed) {
            stopAttack();
        }

        if (currentSong.finished()) {
            Util.showChatMessage("§6Done playing §3" + currentSong.name);
            currentSong = null;
        }
    }
    private void setPlayProgressDisplay() {
        long currentTime = Math.min(currentSong.time, currentSong.length);
        long totalTime = currentSong.length;
        MutableComponent songText = Component.empty()
                .append(Component.literal("Now playing: ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(currentSong.name).withStyle(ChatFormatting.BLUE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(String.format("%s/%s", Util.formatTime(currentTime), Util.formatTime(totalTime))).withStyle(ChatFormatting.DARK_AQUA));
        if (currentSong.looping) {
            if (currentSong.loopCount > 0) {
                songText.append(Component.literal(String.format(" | Loop (%d/%d)", currentSong.currentLoop, currentSong.loopCount)).withStyle(ChatFormatting.GOLD));
            } else {
                songText.append(Component.literal(" | Looping enabled").withStyle(ChatFormatting.GOLD));
            }
        }
        MutableComponent playlistText = Component.empty();
        if (currentPlaylist != null && currentPlaylist.loaded) {
            playlistText = playlistText.append(Component.literal("Playlist: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(currentPlaylist.name).withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.format(" (%s/%s)", currentPlaylist.songNumber, currentPlaylist.songs.size())).withStyle(ChatFormatting.DARK_AQUA));
            if (currentPlaylist.loop) {
                playlistText.append(Component.literal(" | Looping").withStyle(ChatFormatting.GOLD));
            }
            if (currentPlaylist.shuffle) {
                playlistText.append(Component.literal(" | Shuffled").withStyle(ChatFormatting.GOLD));
            }
        }
        ProgressDisplay.getInstance().setText(songText, playlistText);
    }

    // Runs every tick
    private int cleanupTotalBlocksToPlace = 0;
    private LinkedList<BlockPos> cleanupBreakList = new LinkedList<>();
    private LinkedList<BlockPos> cleanupPlaceList = new LinkedList<>();
    private ArrayList<BlockPos> cleanupUnplaceableBlocks = new ArrayList<>();
    private int lastCleanupHash = 0;
    private void handleCleanup() {
        setCleanupProgressDisplay();

        if (buildStartDelay > 0) {
            buildStartDelay--;
            return;
        }
        ClientLevel world = SongPlayer.MC.level;
        // A survival request from the end of building can arrive after cleanup starts.
        // Reassert the cleanup mode through the existing cooldown, clearing queued retries
        // as soon as creative mode is acknowledged so they cannot affect later playback.
        setCreativeIfNeeded();
        if (SongPlayer.MC.gameMode.getPlayerMode() != GameType.CREATIVE) {
            return;
        }

        if (cleanupBreakList.isEmpty() && cleanupPlaceList.isEmpty()) {
            if (buildEndDelay > 0) {
                buildEndDelay--;
                return;
            } else {
                checkCleanupStatus();

                int cleanupHash = 31 * cleanupBreakList.hashCode() + cleanupPlaceList.hashCode();
                if (cleanupHash == lastCleanupHash) { // If loop is detected, stop
                    cleaningUp = false;
                    Util.showChatMessage("§6Stopped restoring original blocks due to infinite loop being detected");
                    if (!cleanupUnplaceableBlocks.isEmpty()) {
                        Util.showChatMessage(String.format("§3%d §6blocks could not be restored", cleanupUnplaceableBlocks.size()));
                    }
                    return;
                } else {
                    lastCleanupHash = cleanupHash;
                }

                lastStage.sendMovementPacketToStagePosition();
            }
        }

        if (!cleanupBreakList.isEmpty()) {
            incrementBreakAllowance();
            while (consumeBreakAllowance()) {
                if (cleanupBreakList.isEmpty()) continue;
                BlockPos bp = cleanupBreakList.poll();
                attackBlock(bp);
            }
            buildEndDelay = 20;
        } else if (!cleanupPlaceList.isEmpty()) {
            incrementPlaceAllowance();
            while (consumePlaceAllowance()) {
                if (cleanupPlaceList.isEmpty()) continue;
                BlockPos bp = cleanupPlaceList.pollFirst();
                BlockState actualBlockState = world.getBlockState(bp);
                BlockState desiredBlockState = originalBlocks.get(bp);
                if (actualBlockState != desiredBlockState) {
                    holdBlock(desiredBlockState, buildSlot);
                    if (!actualBlockState.isAir() && !actualBlockState.liquid()) {
                        attackBlock(bp);
                    }
                    placeBlock(bp);
                }
            }
            buildEndDelay = 20;
        } else {
            originalBlocks.clear();
            cleaningUp = false;
            Util.showChatMessage("§6Finished restoring original blocks");
            if (!cleanupUnplaceableBlocks.isEmpty()) {
                Util.showChatMessage(String.format("§3%d §6blocks could not be restored", cleanupUnplaceableBlocks.size()));
            }
        }
    }
    private void checkCleanupStatus() {
        ClientLevel world = SongPlayer.MC.level;

        cleanupPlaceList.clear();
        cleanupBreakList.clear();
        cleanupUnplaceableBlocks.clear();

        for (BlockPos bp : originalBlocks.keySet()) {
            BlockState actualBlockState = world.getBlockState(bp);
            BlockState desiredBlockState = originalBlocks.get(bp);
            if (actualBlockState != desiredBlockState) {
                if (isPlaceable(desiredBlockState)) {
                    cleanupPlaceList.add(bp);
                }
                if (!actualBlockState.isAir() && !actualBlockState.liquid()) {
                    cleanupBreakList.add(bp);
                }
            }
        }

        cleanupBreakList = cleanupBreakList.stream()
                .sorted((a, b) -> {
                    // First sort by gravity
                    boolean a_grav = SongPlayer.MC.level.getBlockState(a).getBlock() instanceof FallingBlock;
                    boolean b_grav = SongPlayer.MC.level.getBlockState(b).getBlock() instanceof FallingBlock;
                    if (a_grav && !b_grav) {
                        return 1;
                    } else if (!a_grav && b_grav) {
                        return -1;
                    }
                    // If there's gravity, sort by y coordinate
                    if (a_grav && b_grav) {
                        if (a.getY() < b.getY()) {
                            return -1;
                        } else if (a.getY() > b.getY()) {
                            return 1;
                        }
                    }
                    // Then sort by distance
                    int a_dx = a.getX() - lastStage.position.getX();
                    int a_dy = a.getY() - lastStage.position.getY();
                    int a_dz = a.getZ() - lastStage.position.getZ();
                    int b_dx = b.getX() - lastStage.position.getX();
                    int b_dy = b.getY() - lastStage.position.getY();
                    int b_dz = b.getZ() - lastStage.position.getZ();
                    int a_dist = a_dx*a_dx + a_dy*a_dy + a_dz*a_dz;
                    int b_dist = b_dx*b_dx + b_dy*b_dy + b_dz*b_dz;
                    if (a_dist < b_dist) {
                        return -1;
                    } else if (a_dist > b_dist) {
                        return 1;
                    }
                    // Finally sort by angle
                    double a_angle = Math.atan2(a_dz, a_dx);
                    double b_angle = Math.atan2(b_dz, b_dx);
                    if (a_angle < b_angle) {
                        return -1;
                    } else if (a_angle > b_angle) {
                        return 1;
                    } else {
                        return 0;
                    }
                })
                .collect(Collectors.toCollection(LinkedList::new));

        cleanupPlaceList = cleanupPlaceList.stream()
                .sorted((a, b) -> {
                    // First sort by gravity
                    boolean a_grav = originalBlocks.get(a).getBlock() instanceof FallingBlock;
                    boolean b_grav = originalBlocks.get(b).getBlock() instanceof FallingBlock;
                    if (a_grav && !b_grav) {
                        return -1;
                    } else if (!a_grav && b_grav) {
                        return 1;
                    }
                    // If there's gravity, sort by y coordinate
                    if (a_grav && b_grav) {
                        if (a.getY() < b.getY()) {
                            return 1;
                        } else if (a.getY() > b.getY()) {
                            return -1;
                        }
                    }
                    // Then sort by distance
                    int a_dx = a.getX() - lastStage.position.getX();
                    int a_dy = a.getY() - lastStage.position.getY();
                    int a_dz = a.getZ() - lastStage.position.getZ();
                    int b_dx = b.getX() - lastStage.position.getX();
                    int b_dy = b.getY() - lastStage.position.getY();
                    int b_dz = b.getZ() - lastStage.position.getZ();
                    int a_dist = a_dx*a_dx + a_dy*a_dy + a_dz*a_dz;
                    int b_dist = b_dx*b_dx + b_dy*b_dy + b_dz*b_dz;
                    if (a_dist < b_dist) {
                        return -1;
                    } else if (a_dist > b_dist) {
                        return 1;
                    }
                    // Finally sort by angle
                    double a_angle = Math.atan2(a_dz, a_dx);
                    double b_angle = Math.atan2(b_dz, b_dx);
                    if (a_angle < b_angle) {
                        return 1;
                    } else if (a_angle > b_angle) {
                        return -1;
                    } else {
                        return 0;
                    }
                })
                .collect(Collectors.toCollection(LinkedList::new));

        cleanupPlaceList = cleanupPlaceList.reversed();
        cleanupTotalBlocksToPlace = cleanupPlaceList.size();

        boolean noNecessaryBreaks = cleanupBreakList.stream().allMatch(
                bp -> world.getBlockState(bp).getBlock().defaultBlockState().equals(originalBlocks.get(bp).getBlock().defaultBlockState())
        );
        boolean noNecessaryPlacements = cleanupPlaceList.stream().allMatch(
                bp -> bp.equals(lastStage.position)
                || bp.equals(lastStage.position.above())
                || world.getBlockState(bp).getBlock().defaultBlockState().equals(originalBlocks.get(bp).getBlock().defaultBlockState())
        );
        if (noNecessaryBreaks && noNecessaryPlacements) {
            cleanupUnplaceableBlocks.addAll(cleanupPlaceList);
            cleanupPlaceList.clear();
        }
    }
    private void setCleanupProgressDisplay() {
        MutableComponent buildText = Component.empty()
                .append(Component.literal("Rebuilding original blocks | " ).withStyle(ChatFormatting.GOLD))
                .append(Component.literal((cleanupTotalBlocksToPlace - cleanupPlaceList.size()) + "/" + cleanupTotalBlocksToPlace).withStyle(ChatFormatting.DARK_AQUA));
        ProgressDisplay.getInstance().setText(buildText, Component.empty());
    }

    // Resets all internal states like currentSong, and songQueue, which stops all actions
    public void reset() {
        currentSong = null;
        currentPlaylist = null;
        songQueue.clear();
        stage = null;
        buildSlot = -1;
        removeFakePlayer();
        cleaningUp = false;
        dirty = false;
    }
    public void restoreStateAndReset() {
        restoreStateAndReset(true);
    }
    public void restoreStateAndReset(boolean returnToStage) {
        if (returnToStage && lastStage != null) {
            lastStage.movePlayerToStagePosition();
        }
        if (originalGamemode != SongPlayer.MC.gameMode.getPlayerMode() && !Config.getConfig().survivalOnly) {
            if (originalGamemode == GameType.CREATIVE) {
                sendGamemodeCommand(Config.getConfig().creativeCommand);
            }
            else if (originalGamemode == GameType.SURVIVAL) {
                sendGamemodeCommand(Config.getConfig().survivalCommand);
            }
        }
        if (SongPlayer.MC.player.getAbilities().mayfly == false) {
            SongPlayer.MC.player.getAbilities().flying = false;
        }
        if (!Config.getConfig().survivalOnly) restoreBuildSlot();
        reset();
    }
    public void partialResetAndCleanup() {
        restoreBuildSlot();
        currentSong = null;
        currentPlaylist = null;
        songQueue.clear();
        stage = null;
        buildSlot = -1;
        startCleanup();
    }

    public void removeFakePlayer() {
        if (fakePlayer != null) {
            fakePlayer.remove(Entity.RemovalReason.DISCARDED);
            fakePlayer = null;
        }
    }

    // Runs every frame when player is not ingame
    public void onNotIngame() {
        currentSong = null;
        currentPlaylist = null;
        songQueue.clear();
    }

    // Create stage if it doesn't exist and move the player to it
    private void prepareStage() {
        if (stage == null) {
            stage = new Stage();
            lastStage = stage;
            originalBlocks.clear();
            stage.movePlayerToStagePosition();
        }
        else {
            stage.sendMovementPacketToStagePosition();
        }
    }

    private long lastCommandTime = System.currentTimeMillis();
    private String cachedCommand = null;
    private String cachedMessage = null;
    private void sendGamemodeCommand(String command) {
        cachedCommand = command;
    }
    private void sendMessage(String message) {
        cachedMessage = message;
    }
    private void checkCommandCache() {
        long currentTime = System.currentTimeMillis();
        if (currentTime >= lastCommandTime + 1500 && cachedCommand != null) {
            Util.sendCommand(cachedCommand);
            cachedCommand = null;
            lastCommandTime = currentTime;
        }
        else if (currentTime >= lastCommandTime + 500 && cachedMessage != null) {
            if (cachedMessage.startsWith("/")) {
                Util.sendCommand(cachedMessage.substring(1));
            }
            else {
                Util.sendChatMessage(cachedMessage);
            }
            cachedMessage = null;
            lastCommandTime = currentTime;
        }
    }
    private void setCreativeIfNeeded() {
        cachedCommand = null;
        if (SongPlayer.MC.gameMode.getPlayerMode() != GameType.CREATIVE) {
            sendGamemodeCommand(Config.getConfig().creativeCommand);
        }
    }
    private void setSurvivalIfNeeded() {
        cachedCommand = null;
        if (SongPlayer.MC.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            sendGamemodeCommand(Config.getConfig().survivalCommand);
        }
    }

    private final String[] instrumentNames = {"harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};
    private void holdNoteblock(int id, int slot) {
        Inventory inventory = SongPlayer.MC.player.getInventory();
        inventory.setSelectedSlot(slot);
        ((ClientPlayerInteractionManagerAccessor) SongPlayer.MC.gameMode).invokeSyncSelectedSlot();
        int instrument = id/25;
        int note = id%25;
        ItemStack noteblockStack = Items.NOTE_BLOCK.getDefaultInstance();
        noteblockStack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of(
                "instrument", instrumentNames[instrument],
                "note", Integer.toString(note)
        )));
        inventory.getNonEquipmentItems().set(slot, noteblockStack);
        SongPlayer.MC.gameMode.handleCreativeModeItemAdd(noteblockStack, 36 + slot);
    }
    private void holdBlock(BlockState bs, int slot) {
        Inventory inventory = SongPlayer.MC.player.getInventory();
        inventory.setSelectedSlot(slot);
        ((ClientPlayerInteractionManagerAccessor) SongPlayer.MC.gameMode).invokeSyncSelectedSlot();
        ItemStack stack = new ItemStack(bs.getBlock());
        Map<String, String> stateMap = new TreeMap<>();
        bs.getValues().forEach(value -> stateMap.put(value.property().getName(), value.valueName()));
        stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(stateMap));
        inventory.getNonEquipmentItems().set(slot, stack);
        SongPlayer.MC.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
    }
    private void placeBlock(BlockPos bp) {
        double fx = Math.max(0.0, Math.min(1.0, (lastStage.position.getX() + 0.5 - bp.getX())));
        double fy = Math.max(0.0, Math.min(1.0, (lastStage.position.getY() + 0.0 - bp.getY())));
        double fz = Math.max(0.0, Math.min(1.0, (lastStage.position.getZ() + 0.5 - bp.getZ())));
        fx += bp.getX();
        fy += bp.getY();
        fz += bp.getZ();
        doRotateIfNeeded(fx, fy, fz);
        SongPlayer.MC.gameMode.useItemOn(SongPlayer.MC.player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(fx, fy, fz), Direction.UP, bp, false));
        doSwingIfNeeded();
    }
    private void attackBlock(BlockPos bp) {
        doRotateIfNeeded(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
        SongPlayer.MC.gameMode.startDestroyBlock(bp, Direction.UP);
        doSwingIfNeeded();
    }
    private void stopAttack() {
        SongPlayer.MC.gameMode.stopDestroyBlock();
    }
    private void recordBlocks(Iterable<BlockPos> bpList) {
        for (BlockPos bp : bpList) {
            if (!originalBlocks.containsKey(bp)) {
                BlockState bs = SongPlayer.MC.level.getBlockState(bp);
                originalBlocks.put(bp, bs);
            }
        }
    }
    private void recordStageBlocks() {
        recordBlocks(stage.requiredBreaks);
        recordBlocks(stage.missingNotes
                .stream()
                .map(noteId -> stage.noteblockPositions.get(noteId))
                .filter(Objects::nonNull)
                .toList()
        );
    }
    private boolean isPlaceable(BlockState bs) {
        if (bs.getValues().anyMatch(value -> value.property().getName().equals("half")
                && value.valueName().equals("upper"))) {
            return false;
        }
        Block block = bs.getBlock();
        if (bs.isAir() || bs.liquid()) {
            return false;
        } else if (new ItemStack(block).isEmpty()) {
            return false;
        } else if (block instanceof DoorBlock || block instanceof BedBlock) {
            return false;
        } else {
            return true;
        }
    }

    private void doRotateIfNeeded(double lookX, double lookY, double lookZ) {
        if (Config.getConfig().rotate) {
            double d = lookX - (lastStage.position.getX() + 0.5);
            double e = lookY - (lastStage.position.getY() + SongPlayer.MC.player.getEyeHeight());
            double f = lookZ - (lastStage.position.getZ() + 0.5);
            double g = Math.sqrt(d * d + f * f);
            float pitch = Mth.wrapDegrees((float) (-(Mth.atan2(e, g) * 57.2957763671875)));
            float yaw = Mth.wrapDegrees((float) (Mth.atan2(f, d) * 57.2957763671875) - 90.0f);
            if (fakePlayer != null) {
                fakePlayer.setXRot(pitch);
                fakePlayer.setYRot(yaw);
                fakePlayer.setYHeadRot(yaw);
            }
            // Send on ClientConnection instead of networkHandler because mixin overrides sendPacket on networkHandler
            SongPlayer.MC.player.connection.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                    lastStage.position.getX() + 0.5, lastStage.position.getY(), lastStage.position.getZ() + 0.5,
                    yaw, pitch,
                    true, false));
        }
    }
    private void doSwingIfNeeded() {
        if (Config.getConfig().swing) {
            SongPlayer.MC.player.swing(InteractionHand.MAIN_HAND);
            if (fakePlayer != null) {
                fakePlayer.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private void getAndSaveBuildSlot() {
        buildSlot = SongPlayer.MC.player.getInventory().getSuitableHotbarSlot();
        prevHeldItem = SongPlayer.MC.player.getInventory().getItem(buildSlot);
    }
    private void restoreBuildSlot() {
        if (buildSlot != -1) {
            SongPlayer.MC.player.getInventory().setItem(buildSlot, prevHeldItem);
            SongPlayer.MC.gameMode.handleCreativeModeItemAdd(prevHeldItem, 36 + buildSlot);
            buildSlot = -1;
        }
    }

    // Number of blocks allowed to be broken
    private double breakAllowance = 0.0;
    // Called every tick where block breaking is being handled
    private void incrementBreakAllowance() {
        breakAllowance += Config.getConfig().breakSpeed / 20.0;
    }
    // If there is enough breakAllowance, decrement breakAllowance and return true. Otherwise, return false.
    private boolean consumeBreakAllowance() {
        if (breakAllowance >= 1.0) {
            breakAllowance--;
            return true;
        } else {
            return false;
        }
    }

    // Number of blocks allowed to be placed
    private double placeAllowance = 0.0;
    // Called every tick where block placement are being handled
    private void incrementPlaceAllowance() {
        placeAllowance += Config.getConfig().placeSpeed / 20.0;
    }
    // If there is enough placeAllowance, decrement placeAllowance and return true. Otherwise, return false.
    private boolean consumePlaceAllowance() {
        if (placeAllowance >= 1.0) {
            placeAllowance--;
            return true;
        } else {
            return false;
        }
    }

    public boolean isIdle() {
        return currentSong == null && currentPlaylist == null && songQueue.isEmpty() && !cleaningUp && !dirty;
    }
}
