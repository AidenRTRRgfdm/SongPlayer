package com.github.hhhzzzsss.songplayer.test;

import com.github.hhhzzzsss.songplayer.Config;
import com.github.hhhzzzsss.songplayer.playing.NoteBlockNotes;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import com.github.hhhzzzsss.songplayer.playing.Stage;
import com.github.hhhzzzsss.songplayer.song.Note;
import com.github.hhhzzzsss.songplayer.song.Song;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Opt-in protocol regression against only a root-controlled loopback 26.3 server. */
@SuppressWarnings("UnstableApiUsage")
public final class SongPlayerViaGameTest implements FabricClientGameTest {
    private static final int WAIT_TICKS = 1200;
    private static final BlockPos ORIGIN = new BlockPos(0, 70, 0);
    private static final String INVALID_MOVEMENT = "multiplayer.disconnect.invalid_player_movement";

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = System.getProperty("songplayer.viaTestServer");
        if (address == null) return;
        require(address.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}|\\[::1\\]:[0-9]{1,5}"),
                "Protocol tests require an explicit loopback server address");
        require(FabricLoader.getInstance().isModLoaded("viafabricplus"), "ViaFabricPlus is absent from the protocol fixture");
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(client -> {
            client.options.renderDistance().set(3);
            client.options.guiScale().set(2);
            configure();
            selectProtocol263();
        });

        connect(context, address);
        verifyStrictDuplicatePositionBaseline(context);
        connect(context, address);
        verifyRotationOnlyControl(context);
        prepare(context);
        verifySong(context, false);
        verifySong(context, true);
        verifyStopDuringBuilding(context);
        context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        await(context, "fixture disconnects", client -> client.level == null && client.player == null);
        System.out.println("SONGPLAYER_VIA_26_3_GAMETEST_PASS: Minecraft 26.2 + ViaFabricPlus target26.3; "
                + "native strict duplicate-position disconnect reproduced; one position plus rotation-only control; "
                + "rotate=false/true construction and real server note playback; fake player; vanilla teleport ACKs during building; "
                + "automatic cleanup and original block/hotbar restoration; stop during building");
    }

    private static void configure() {
        Config config = Config.getConfig();
        config.prefix = "$";
        config.stageType = Stage.StageType.DEFAULT;
        config.creativeCommand = "gamemode creative";
        config.survivalCommand = "gamemode survival";
        config.showFakePlayer = true;
        config.rotate = false;
        config.swing = true;
        config.breakSpeed = 40;
        config.placeSpeed = 20;
        config.velocityThreshold = 0;
        config.autoCleanup = true;
        config.survivalOnly = false;
        config.doAnnouncement = false;
        handler().reset();
    }

    private static void selectProtocol263() {
        try {
            Class<?> protocol = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object target = protocol.getField("v26_3").get(null);
            Object api = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("api").invoke(null);
            Class<?> apiType = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusAPI");
            apiType.getMethod("setTargetVersion", protocol).invoke(api, target);
            Object selected = apiType.getMethod("targetVersion").invoke(api);
            require(target.equals(selected), "ViaFabricPlus did not retain target26.3");
            System.out.println("SONGPLAYER_VIA_PROTOCOL_TARGET: " + selected);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException invocation ? invocation.getCause() : error;
            throw new AssertionError("Could not select ViaFabricPlus 26.3 protocol", cause);
        }
    }

    private static void connect(ClientGameTestContext context, String address) {
        context.runOnClient(client -> {
            handler().reset();
            selectProtocol263();
            ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                    new ServerData("protocol-test", address, ServerData.Type.OTHER), false, null);
        });
        await(context, "client connects to isolated native26.3 server", client -> client.player != null && client.level != null
                && client.getConnection() != null && !(client.gui.screen() instanceof DisconnectedScreen));
        context.waitTicks(40);
        requireConnected(context, "connection settles before protocol packets");
    }

    private static void verifyStrictDuplicatePositionBaseline(ClientGameTestContext context) {
        context.runOnClient(client -> {
            require(handler().isIdle(), "Baseline must bypass active SongPlayer movement handling");
            Connection connection = client.getConnection().getConnection();
            connection.send(ServerboundClientTickEndPacket.INSTANCE);
            connection.send(position(client));
            connection.send(position(client));
        });
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, 100);
        context.runOnClient(client -> {
            Component narration = ((DisconnectedScreen) client.gui.screen()).getNarrationMessage();
            require(containsTranslation(narration, INVALID_MOVEMENT)
                            || narration.getString().contains("Invalid move player packet received"),
                    "Baseline disconnected for another reason: " + narration.getString());
            require(client.level == null, "Strict baseline did not close the world connection");
            System.out.println("SONGPLAYER_VIA_STRICT_BASELINE_PASS: " + narration.getString());
        });
    }

    private static boolean containsTranslation(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translation && translation.getKey().equals(key)) return true;
        return component.getSiblings().stream().anyMatch(sibling -> containsTranslation(sibling, key));
    }

    private static void verifyRotationOnlyControl(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Connection connection = client.getConnection().getConnection();
            connection.send(ServerboundClientTickEndPacket.INSTANCE);
            connection.send(position(client));
            for (int index = 0; index < 8; index++) {
                connection.send(new ServerboundMovePlayerPacket.Rot(client.player.getYRot() + index,
                        client.player.getXRot(), client.player.onGround(), client.player.horizontalCollision));
            }
            connection.send(ServerboundClientTickEndPacket.INSTANCE);
        });
        context.waitTicks(40);
        requireConnected(context, "one position plus multiple rotation packets control");
        System.out.println("SONGPLAYER_VIA_ROTATION_CONTROL_PASS");
    }

    private static ServerboundMovePlayerPacket.PosRot position(Minecraft client) {
        return new ServerboundMovePlayerPacket.PosRot(client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getYRot(), client.player.getXRot(), client.player.onGround(), client.player.horizontalCollision);
    }

    private static void prepare(ClientGameTestContext context) {
        sendCommand(context, "gamemode creative");
        await(context, "creative command acknowledged", client -> client.gameMode.getPlayerMode() == GameType.CREATIVE);
        sendCommand(context, "time set noon");
        sendCommand(context, "fill -5 67 -5 5 75 5 minecraft:air");
        sendCommand(context, "fill -5 69 -5 5 69 5 minecraft:stone");
        sendCommand(context, "tp @s 0.5 70 0.5 0 35");
        await(context, "isolated stone arena reaches client", client -> client.player.blockPosition().equals(ORIGIN)
                && client.level.getBlockState(ORIGIN.below()).is(Blocks.STONE));
        context.waitTicks(5);
        context.runOnClient(client -> {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = new ItemStack(Items.DIAMOND, slot + 1);
                client.player.getInventory().setItem(slot, stack);
                client.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
            }
        });
        context.waitTicks(5);
    }

    private static void verifySong(ClientGameTestContext context, boolean rotate) {
        Map<BlockPos, BlockState> baseline = context.computeOnClient(SongPlayerViaGameTest::snapshot);
        List<ItemStack> inventory = context.computeOnClient(SongPlayerViaGameTest::hotbar);
        context.runOnClient(client -> {
            require(handler().isIdle(), "Previous protocol fixture did not return idle");
            Config.getConfig().rotate = rotate;
            BlockEventProbe.reset();
            ViaMovementProbe.reset();
            handler().setSong(song("Via rotate=" + rotate, 3000));
        });
        await(context, "stage starts recording original blocks", client -> handler().building && !handler().originalBlocks.isEmpty());
        // Same stage position with a new server-supplied look forces the ordinary teleport ACK path
        // while SongPlayer is actively building. The server's strict parser remains authoritative.
        sendCommand(context, "tp @s 0.5 70 0.5 90 25");
        await(context, "vanilla teleport acknowledgement passes through active movement handling", client -> ViaMovementProbe.acknowledgements() > 0);
        await(context, "constructed stage enters survival playback", client -> handler().currentSong != null
                && !handler().building && client.gameMode.getPlayerMode() == GameType.SURVIVAL);
        Set<BlockPos> notes = context.computeOnClient(client -> {
            verifyStage(client);
            require(handler().fakePlayer != null && !handler().fakePlayer.isRemoved(), "Protocol playback lacks fake player");
            require(client.level.getEntity(handler().fakePlayer.getId()) == handler().fakePlayer, "Fake player is detached");
            return new HashSet<>(handler().stage.noteblockPositions.values());
        });
        await(context, "real native26.3 note events for all four notes", client -> BlockEventProbe.playedAll(notes));
        context.takeScreenshot("songplayer-via-26.3-playing-rotate-" + rotate);
        await(context, "natural completion and auto-cleanup", client -> handler().isIdle());
        await(context, "original creative game mode restored", client -> client.gameMode.getPlayerMode() == GameType.CREATIVE);
        verifyRestored(context, baseline, inventory);
        context.runOnClient(client -> System.out.println("SONGPLAYER_VIA_PLAYBACK_PASS: rotate=" + rotate + " " + ViaMovementProbe.diagnostics()));
    }

    private static void verifyStopDuringBuilding(ClientGameTestContext context) {
        Map<BlockPos, BlockState> baseline = context.computeOnClient(SongPlayerViaGameTest::snapshot);
        List<ItemStack> inventory = context.computeOnClient(SongPlayerViaGameTest::hotbar);
        context.runOnClient(client -> {
            Config.getConfig().rotate = true;
            Config.getConfig().placeSpeed = 1;
            ViaMovementProbe.reset();
            handler().setSong(song("Via stop during building", 60000));
        });
        await(context, "at least one note is placed while building is still active", client -> handler().building
                && !handler().originalBlocks.isEmpty() && handler().stage.noteblockPositions.values().stream()
                .anyMatch(pos -> NoteBlockNotes.getNoteId(client.level.getBlockState(pos)) >= 0));
        sendCommand(context, "tp @s 0.5 70 0.5 180 30");
        await(context, "teleport acknowledgement survives slow construction", client -> ViaMovementProbe.acknowledgements() > 0);
        context.runOnClient(client -> {
            require(handler().building, "Slow fixture finished building before stop");
            Config.getConfig().placeSpeed = 20;
            ChatProbe.reset();
            client.getConnection().sendChat("$stop");
            require(ChatProbe.hasOutput("Stopped playing and switched to cleanup"), "$stop did not start cleanup while building");
        });
        await(context, "stop during building cleans up", client -> handler().isIdle());
        await(context, "stopped fixture restores creative", client -> client.gameMode.getPlayerMode() == GameType.CREATIVE);
        verifyRestored(context, baseline, inventory);
        System.out.println("SONGPLAYER_VIA_STOP_DURING_BUILD_PASS");
    }

    private static Song song(String name, long length) {
        Song song = new Song(name);
        song.add(new Note(0, 0));
        song.add(new Note(24, 350));
        song.add(new Note(25, 900));
        song.add(new Note(399, 1400));
        song.length = length;
        return song;
    }

    private static void verifyStage(Minecraft client) {
        require(handler().stage.noteblockPositions.size() == 4, "Protocol stage dropped a required note");
        handler().stage.noteblockPositions.forEach((id, pos) -> {
            require(NoteBlockNotes.getNoteId(client.level.getBlockState(pos)) == id,
                    "Protocol construction produced another note: target=" + id + " state=" + client.level.getBlockState(pos));
            require(client.level.getBlockState(pos.above()).isAir(), "Protocol note is obstructed at " + pos);
        });
    }

    private static Map<BlockPos, BlockState> snapshot(Minecraft client) {
        Map<BlockPos, BlockState> snapshot = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(-5, 67, -5, 5, 75, 5)) snapshot.put(pos.immutable(), client.level.getBlockState(pos));
        return snapshot;
    }

    private static List<ItemStack> hotbar(Minecraft client) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) stacks.add(client.player.getInventory().getItem(slot).copy());
        return stacks;
    }

    private static void verifyRestored(ClientGameTestContext context, Map<BlockPos, BlockState> baseline, List<ItemStack> inventory) {
        requireConnected(context, "playback/cleanup remains connected to strict native server");
        context.runOnClient(client -> {
            require(handler().originalBlocks.isEmpty(), "Original-block bookkeeping remains after cleanup");
            require(handler().fakePlayer == null, "Fake player remains after cleanup");
            baseline.forEach((pos, state) -> require(client.level.getBlockState(pos).equals(state),
                    "Protocol cleanup did not restore " + pos + ": expected=" + state + " actual=" + client.level.getBlockState(pos)));
            for (int slot = 0; slot < inventory.size(); slot++) {
                require(ItemStack.matches(inventory.get(slot), client.player.getInventory().getItem(slot)),
                        "Protocol cleanup changed hotbar slot " + slot);
            }
        });
    }

    private static void sendCommand(ClientGameTestContext context, String command) {
        context.runOnClient(client -> {
            require(client.getConnection() != null, "Disconnected before local fixture command: " + command);
            client.getConnection().sendCommand(command);
        });
    }

    private static void requireConnected(ClientGameTestContext context, String description) {
        context.runOnClient(client -> require(client.player != null && client.level != null
                && client.getConnection() != null && !(client.gui.screen() instanceof DisconnectedScreen),
                description + ": " + disconnectReason(client)));
    }

    private static String disconnectReason(Minecraft client) {
        return client.gui.screen() instanceof DisconnectedScreen screen ? screen.getNarrationMessage().getString() : "no disconnect screen";
    }

    private static void await(ClientGameTestContext context, String description, Predicate<Minecraft> condition) {
        try {
            context.waitFor(client -> {
                if (client.gui.screen() instanceof DisconnectedScreen) {
                    throw new AssertionError(description + " disconnected: " + disconnectReason(client));
                }
                return condition.test(client);
            }, WAIT_TICKS);
        } catch (AssertionError error) {
            String diagnostic = context.computeOnClient(client -> "song=" + (handler().currentSong == null ? "none" : handler().currentSong.name)
                    + " building=" + handler().building + " cleanup=" + handler().cleaningUp + " dirty=" + handler().dirty
                    + " mode=" + (client.gameMode == null ? "none" : client.gameMode.getPlayerMode())
                    + " " + ViaMovementProbe.diagnostics() + " disconnect=" + disconnectReason(client));
            throw new AssertionError(description + " failed: " + diagnostic, error);
        }
    }

    private static SongHandler handler() { return SongHandler.getInstance(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
