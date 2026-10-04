package com.github.hhhzzzsss.songplayer.test;

import com.github.hhhzzzsss.songplayer.Config;
import com.github.hhhzzzsss.songplayer.conversion.SPConverter;
import com.github.hhhzzzsss.songplayer.item.SongItemConfirmationScreen;
import com.github.hhhzzzsss.songplayer.item.SongItemLoaderThread;
import com.github.hhhzzzsss.songplayer.item.SongItemUtils;
import com.github.hhhzzzsss.songplayer.playing.NoteBlockNotes;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import com.github.hhhzzzsss.songplayer.song.Note;
import com.github.hhhzzzsss.songplayer.song.Song;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** End-to-end checks in an isolated Fabric-created world, using actual server packets. */
@SuppressWarnings("UnstableApiUsage")
public final class SongPlayerGameTest implements FabricClientGameTest {
    private static final int WAIT_TICKS = 1000;
    private static final BlockPos ORIGIN = new BlockPos(0, 70, 0);
    private static final NoteBlockInstrument[] INSTRUMENTS = {
            NoteBlockInstrument.HARP, NoteBlockInstrument.BASEDRUM, NoteBlockInstrument.SNARE,
            NoteBlockInstrument.HAT, NoteBlockInstrument.BASS, NoteBlockInstrument.FLUTE,
            NoteBlockInstrument.BELL, NoteBlockInstrument.GUITAR, NoteBlockInstrument.CHIME,
            NoteBlockInstrument.XYLOPHONE, NoteBlockInstrument.IRON_XYLOPHONE,
            NoteBlockInstrument.COW_BELL, NoteBlockInstrument.DIDGERIDOO,
            NoteBlockInstrument.BIT, NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.renderDistance().set(3);
            client.options.guiScale().set(2);
            configure();
            verifyNoteIds();
        });
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            await(context, "client joins isolated world", client -> client.player != null && client.level != null);
            world.getConnection().waitForChunksRender();
            prepare(context, world);
            verifyCommands(context);
            ItemStack item = context.computeOnClient(client -> verifySongItem(client));
            verifyConfirmationCancel(context, item);
            verifyBuildPlayCleanup(context, world, item);
            verifySurvivalTuning(context, world);
        }
        context.runOnClient(client -> {
            require(client.level == null, "Disposable world did not disconnect");
            require(handler().currentSong == null && handler().songQueue.isEmpty(), "Playback survived disconnect");
        });
        System.out.println("SONGPLAYER_26_2_GAMETEST_PASS: 800 semantic note states; new instruments excluded; "
                + "chat command interception and native autocomplete; SP format and NBT item persistence; confirmation Cancel/Play; creative stage construction; "
                + "server note events; fake player; altered-stage rebuild; inventory and block cleanup; survival tuning");
    }

    private static void configure() {
        Config config = Config.getConfig();
        config.prefix = "$";
        config.stageType = Stage.StageType.DEFAULT;
        config.creativeCommand = "gamemode creative";
        config.survivalCommand = "gamemode survival";
        config.showFakePlayer = true;
        config.rotate = true;
        config.swing = true;
        config.breakSpeed = 40;
        config.placeSpeed = 20;
        config.velocityThreshold = 0;
        config.autoCleanup = true;
        config.survivalOnly = false;
        config.doAnnouncement = false;
        handler().reset();
        BlockEventProbe.reset();
    }

    private static void verifyNoteIds() {
        for (int instrument = 0; instrument < INSTRUMENTS.length; instrument++) {
            for (int pitch = 0; pitch < 25; pitch++) {
                for (boolean powered : new boolean[]{false, true}) {
                    BlockState state = Blocks.NOTE_BLOCK.defaultBlockState()
                            .setValue(NoteBlock.INSTRUMENT, INSTRUMENTS[instrument])
                            .setValue(NoteBlock.NOTE, pitch).setValue(NoteBlock.POWERED, powered);
                    require(NoteBlockNotes.getNoteId(state) == instrument * 25 + pitch,
                            "Wrong semantic note id: " + state);
                }
            }
        }
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            if (List.of(INSTRUMENTS).contains(instrument)) continue;
            require(NoteBlockNotes.getNoteId(Blocks.NOTE_BLOCK.defaultBlockState()
                    .setValue(NoteBlock.INSTRUMENT, instrument)) == -1,
                    "New/non-song instrument alias: " + instrument);
        }
        require(NoteBlockNotes.getNoteId(Blocks.AIR.defaultBlockState()) == -1, "Air is a song note");
        require(NoteBlockNotes.getNoteId(Blocks.STONE.defaultBlockState()) == -1, "Stone is a song note");
    }

    private static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("fill -5 67 -5 5 75 5 minecraft:air");
        world.getServer().runCommand("fill -5 69 -5 5 69 5 minecraft:stone");
        world.getServer().runCommand("tp @a 0.5 70 0.5 0 35");
        await(context, "fixture arena and creative game mode", client -> client.player != null
                && client.gameMode.getPlayerMode() == GameType.CREATIVE
                && client.player.blockPosition().equals(ORIGIN)
                && client.level.getBlockState(ORIGIN.below()).is(Blocks.STONE));
        context.waitTicks(3);
        context.runOnClient(client -> {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = new ItemStack(Items.DIAMOND, slot + 1);
                client.player.getInventory().setItem(slot, stack);
                client.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
            }
        });
        context.waitTicks(3);
    }

    private static ItemStack verifySongItem(Minecraft client) {
        try {
            Song source = creativeSong();
            source.looping = true;
            source.loopCount = 2;
            source.loopPosition = 350;
            byte[] data = SPConverter.getBytesFromSong(source);
            Song decoded = SPConverter.getSongFromBytes(data, "fallback.sp");
            require(decoded.name.equals(source.name) && decoded.length == source.length, "SP metadata lost");
            require(decoded.looping && decoded.loopCount == 2 && decoded.loopPosition == 350, "SP loop metadata lost");
            assertSameNotes(source, decoded);
            source.looping = false;
            data = SPConverter.getBytesFromSong(source);

            Config.getConfig().velocityThreshold = 50;
            Song filtered = new Song("filtered");
            filtered.add(new Note(0, 0, 49));
            filtered.add(new Note(399, 900, 50));
            filtered.length = 1000;
            Song filteredRoundtrip = SPConverter.getSongFromBytes(SPConverter.getBytesFromSong(filtered), "filtered.sp");
            require(filteredRoundtrip.size() == 1 && filteredRoundtrip.get(0).noteId == 399
                    && filteredRoundtrip.get(0).time == 900, "Velocity threshold or encoded delta changed");
            Config.getConfig().velocityThreshold = 0;

            CompoundTag unrelated = new CompoundTag();
            unrelated.putString("Owner", "preserve-me");
            ItemStack base = new ItemStack(Items.PAPER, 3);
            base.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
            ItemStack item = SongItemUtils.createSongItem(base, data, "fixture.sp", "Port test ♪");
            var ops = client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var encoded = ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
            ItemStack restored = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
            require(restored.getCount() == 3 && restored.is(Items.PAPER), "Item identity/count changed");
            require(SongItemUtils.isSongItem(restored), "Song marker lost on item codec roundtrip");
            require(java.util.Arrays.equals(data, SongItemUtils.getSongData(restored)), "Song bytes lost on item roundtrip");
            require(restored.get(DataComponents.CUSTOM_DATA).copyTag().getString("Owner").orElseThrow()
                    .equals("preserve-me"), "Unrelated custom item data overwritten");
            require(restored.get(DataComponents.CUSTOM_NAME).getString().equals("Port test ♪"), "Song item display name lost");
            require(restored.get(DataComponents.LORE).lines().size() == 4, "Song item lore missing");
            SongItemLoaderThread loader = new SongItemLoaderThread(restored);
            loader.run();
            require(loader.exception == null && loader.song != null, "Song item loader failed: " + loader.exception);
            require(loader.song.name.equals("Port test ♪"), "Song item display name did not override file metadata");
            assertSameNotes(source, loader.song);
            require(loader.maxNotesPerSecond == 3, "Song item note density changed");
            require(Math.abs(loader.avgNotesPerSecond - 4 * 1000.0 / source.length) < 0.00001,
                    "Song item average density changed");
            SongItemUtils.updateSongItemTag(restored, tag -> tag.putString(SongItemUtils.DISPLAY_NAME_KEY, "Port test ♪"));
            require(SongItemUtils.getSongItemTag(restored).orElseThrow().getString(SongItemUtils.DISPLAY_NAME_KEY)
                    .orElseThrow().equals("Port test ♪"), "Song tag update did not persist");
            return restored;
        } catch (IOException error) {
            throw new AssertionError("Song item conversion failed", error);
        }
    }

    private static void verifyCommands(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ChatProbe.reset();
            client.getConnection().sendChat("$help");
            client.getConnection().sendChat("$songs");
            client.getConnection().sendChat("$status");
            require(ChatProbe.hasOutput("Commands -") && ChatProbe.hasOutput("$play"), "$help output missing");
            require(ChatProbe.hasOutput(".minecraft/songs"), "$songs output missing");
            require(ChatProbe.hasOutput("No song is currently playing"), "$status output missing");
            require(ChatProbe.sent().isEmpty(), "Local commands were sent to the server: " + ChatProbe.sent());
            client.getConnection().sendChat("SongPlayer network probe");
            require(ChatProbe.sent().equals(List.of("SongPlayer network probe")), "Ordinary chat was intercepted or probe failed");
        });
        context.getInput().pressKey(GLFW.GLFW_KEY_T);
        context.waitForScreen(ChatScreen.class);
        context.getInput().typeChars("$statu");
        context.waitTick();
        context.runOnClient(client -> {
            CommandSuggestions suggestions = (CommandSuggestions) field(client.gui.screen(), "commandSuggestions");
            @SuppressWarnings("unchecked")
            CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> pending =
                    (CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>) field(suggestions, "pendingSuggestions");
            require(pending != null && pending.isDone() && pending.getNow(null).getList().stream()
                    .anyMatch(suggestion -> suggestion.getText().equals("$status")), "Native chat missed SongPlayer suggestions");
            require(field(suggestions, "suggestions") != null, "Native autocomplete popup is absent");
            ChatProbe.reset();
        });
        context.takeScreenshot("songplayer-native-command-suggestions-26.2");
        context.getInput().pressKey(GLFW.GLFW_KEY_TAB);
        context.waitTick();
        context.runOnClient(client -> require(((EditBox) field(client.gui.screen(), "input")).getValue().strip().equals("$status"),
                "Tab did not apply SongPlayer autocomplete"));
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        context.waitForScreen(null);
        context.runOnClient(client -> {
            require(ChatProbe.hasOutput("No song is currently playing"), "Entered autocomplete did not run $status");
            require(ChatProbe.sent().isEmpty(), "Entered autocomplete leaked to server chat");
        });
    }

    private static void verifyConfirmationCancel(ClientGameTestContext context, ItemStack item) {
        openConfirmation(context, item);
        context.takeScreenshot("songplayer-song-item-confirmation-26.2");
        clickButton(context, "Cancel");
        context.waitForScreen(null);
        context.runOnClient(client -> require(handler().isIdle(), "Cancel started playback"));
    }

    private static void verifyBuildPlayCleanup(ClientGameTestContext context, TestSingleplayerContext world, ItemStack item) {
        Map<BlockPos, BlockState> original = context.computeOnClient(client -> snapshot(client));
        List<ItemStack> inventory = context.computeOnClient(client -> hotbar(client));
        openConfirmation(context, item);
        context.runOnClient(client -> BlockEventProbe.reset());
        clickButton(context, "Play");
        context.waitForScreen(null);
        await(context, "item starts stage construction", client -> handler().currentSong != null && handler().stage != null);
        await(context, "stage construction and survival playback", client -> handler().currentSong != null
                && !handler().building && client.gameMode.getPlayerMode() == GameType.SURVIVAL);
        Set<BlockPos> notes = context.computeOnClient(client -> {
            require(handler().originalBlocks.size() > 0, "Construction did not record original blocks");
            require(handler().fakePlayer != null && !handler().fakePlayer.isRemoved(), "Fake player missing");
            require(client.level.getEntity(handler().fakePlayer.getId()) == handler().fakePlayer, "Fake player not attached to level");
            verifyStage(client);
            return new HashSet<>(handler().stage.noteblockPositions.values());
        });
        await(context, "real server note events for every song note", client -> BlockEventProbe.playedAll(notes));
        context.takeScreenshot("songplayer-built-stage-playing-26.2");

        BlockPos altered = notes.iterator().next();
        setBlock(world, altered, "minecraft:air");
        await(context, "altered stage enters rebuild", client -> handler().building);
        await(context, "altered stage rebuilt", client -> handler().currentSong != null && !handler().building
                && NoteBlockNotes.getNoteId(client.level.getBlockState(altered)) >= 0);
        context.runOnClient(client -> {
            verifyStage(client);
            ChatProbe.reset();
            client.getConnection().sendChat("$stop");
            require(ChatProbe.hasOutput("Stopped playing and switched to cleanup"), "$stop did not start automatic cleanup");
            require(ChatProbe.sent().isEmpty(), "$stop leaked to server chat");
        });
        await(context, "automatic cleanup completes", client -> handler().isIdle());
        await(context, "original creative game mode restored", client -> client.gameMode.getPlayerMode() == GameType.CREATIVE);
        context.runOnClient(client -> {
            require(handler().fakePlayer == null, "Cleanup retained fake player");
            require(handler().originalBlocks.isEmpty(), "Cleanup retained original block bookkeeping");
            original.forEach((pos, expected) -> require(client.level.getBlockState(pos).equals(expected),
                    "Cleanup did not restore " + pos + ": expected " + expected + " got " + client.level.getBlockState(pos)));
            for (int slot = 0; slot < inventory.size(); slot++) {
                require(ItemStack.matches(inventory.get(slot), client.player.getInventory().getItem(slot)),
                        "Build/cleanup overwrote original hotbar slot " + slot);
            }
        });
        context.takeScreenshot("songplayer-restored-stage-26.2");
    }

    private static void verifySurvivalTuning(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos harp = ORIGIN.offset(1, -1, 0);
        BlockPos bass = ORIGIN.offset(-1, -1, 0);
        setBlock(world, harp.below(), "minecraft:dirt");
        setBlock(world, bass.below(), "minecraft:oak_planks");
        setBlock(world, harp, "minecraft:note_block[instrument=harp,note=2]");
        setBlock(world, bass, "minecraft:note_block[instrument=bass,note=1]");
        world.getServer().runCommand("gamemode survival @a");
        await(context, "survival fixture reaches client", client -> client.gameMode.getPlayerMode() == GameType.SURVIVAL
                && NoteBlockNotes.getNoteId(client.level.getBlockState(harp)) == 2
                && NoteBlockNotes.getNoteId(client.level.getBlockState(bass)) == 101);
        context.runOnClient(client -> {
            Config.getConfig().survivalOnly = true;
            Config.getConfig().autoCleanup = false;
            Song song = new Song("Survival tuning");
            song.add(new Note(12, 0));
            song.add(new Note(107, 400));
            song.length = 60000;
            BlockEventProbe.reset();
            handler().setSong(song);
        });
        await(context, "survival notes tuned without replacing blocks", client -> !handler().building
                && handler().currentSong != null && NoteBlockNotes.getNoteId(client.level.getBlockState(harp)) == 12
                && NoteBlockNotes.getNoteId(client.level.getBlockState(bass)) == 107);
        context.runOnClient(client -> BlockEventProbe.reset());
        context.runOnClient(client -> handler().currentSong.setTime(0));
        await(context, "survival playback emits real note events", client -> BlockEventProbe.playedAll(Set.of(harp, bass)));
        context.runOnClient(client -> {
            require(client.gameMode.getPlayerMode() == GameType.SURVIVAL, "Survival-only mode changed game mode");
            require(handler().originalBlocks.isEmpty(), "Survival-only mode recorded replacements");
            handler().currentSong.setTime(handler().currentSong.length + 1);
        });
        await(context, "survival-only playback returns idle", client -> handler().isIdle());
    }

    private static Song creativeSong() {
        Song song = new Song("SongPlayer fixture ♪");
        song.add(new Note(399, 1400));
        song.add(new Note(25, 900));
        song.add(new Note(24, 350));
        song.add(new Note(0, 0));
        song.length = 60000;
        song.sort();
        return song;
    }

    private static void assertSameNotes(Song expected, Song actual) {
        require(expected.size() == actual.size(), "Note count changed on conversion");
        for (int index = 0; index < expected.size(); index++) {
            require(expected.get(index).noteId == actual.get(index).noteId
                    && expected.get(index).time == actual.get(index).time, "Converted note changed at index " + index);
        }
    }

    private static void verifyStage(Minecraft client) {
        require(handler().stage.noteblockPositions.size() == 4, "Stage dropped a required note");
        handler().stage.noteblockPositions.forEach((noteId, pos) -> {
            require(NoteBlockNotes.getNoteId(client.level.getBlockState(pos)) == noteId,
                    "Constructed note differs from song: " + pos + " target=" + noteId + " actual=" + client.level.getBlockState(pos));
            require(client.level.getBlockState(pos.above()).isAir(), "Constructed note is obstructed: " + pos);
        });
    }

    private static Map<BlockPos, BlockState> snapshot(Minecraft client) {
        Map<BlockPos, BlockState> snapshot = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(-5, 67, -5, 5, 75, 5)) {
            snapshot.put(pos.immutable(), client.level.getBlockState(pos));
        }
        return snapshot;
    }

    private static List<ItemStack> hotbar(Minecraft client) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) stacks.add(client.player.getInventory().getItem(slot).copy());
        return stacks;
    }

    private static void openConfirmation(ClientGameTestContext context, ItemStack item) {
        context.runOnClient(client -> {
            try { client.gui.setScreen(new SongItemConfirmationScreen(item.copy())); }
            catch (IOException error) { throw new AssertionError(error); }
        });
        context.waitForScreen(SongItemConfirmationScreen.class);
        await(context, "song item screen loads its actionable buttons", client -> client.gui.screen() instanceof SongItemConfirmationScreen
                && client.gui.screen().children().stream().filter(child -> child instanceof Button).count() == 2);
        context.waitTicks(2);
    }

    private static void clickButton(ClientGameTestContext context, String label) {
        double[] raw = context.computeOnClient(client -> {
            Button button = client.gui.screen().children().stream().filter(child -> child instanceof Button)
                    .map(child -> (Button) child).filter(candidate -> candidate.getMessage().getString().equals(label))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            return new double[]{(button.getX() + button.getWidth() / 2.0) * client.getWindow().getScreenWidth()
                    / client.getWindow().getGuiScaledWidth(),
                    (button.getY() + button.getHeight() / 2.0) * client.getWindow().getScreenHeight()
                    / client.getWindow().getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(raw[0], raw[1]);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    private static void setBlock(TestSingleplayerContext world, BlockPos pos, String state) {
        world.getServer().runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + state);
    }

    private static void await(ClientGameTestContext context, String description, Predicate<Minecraft> condition) {
        try { context.waitFor(condition, WAIT_TICKS); }
        catch (AssertionError error) {
            String diagnostic = context.computeOnClient(client -> "song=" + (handler().currentSong == null ? "none" : handler().currentSong.name)
                    + " building=" + handler().building + " cleanup=" + handler().cleaningUp + " dirty=" + handler().dirty
                    + " mode=" + (client.gameMode == null ? "none" : client.gameMode.getPlayerMode())
                    + " noteEvents=" + BlockEventProbe.played()
                    + " missing=" + (handler().stage == null ? "no stage" : handler().stage.missingNotes));
            throw new AssertionError(description + " timed out: " + diagnostic, error);
        }
    }

    private static SongHandler handler() { return SongHandler.getInstance(); }
    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Missing native test field " + name, error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
