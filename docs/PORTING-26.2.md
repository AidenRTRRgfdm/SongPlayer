# How SongPlayer was ported to Minecraft 26.2

This fork ports SongPlayer 3.3.5 from Minecraft 1.21.9 to 26.2, retaining its song formats, local commands, stage construction, playback, song items, survival tuning, and cleanup. The starting point was upstream commit [`36f9d1f3dda4cd7b5728e6e4fb54e0a95ff9c9d8`](https://github.com/hhhzzzsss/SongPlayer/commit/36f9d1f3dda4cd7b5728e6e4fb54e0a95ff9c9d8), titled “Fix fakeplayer crash.”

## 1. Convert names while the old Minecraft version still matches

Minecraft 26.2 uses the unobfuscated official names. The old Java code used Yarn names, so the first step was a source migration in a separate scratch checkout. Fabric's [Loom migration guide](https://docs.fabricmc.net/1.21.11/develop/porting/mappings/loom) requires the migration target to match the Minecraft version currently configured in the project.

The scratch checkout retained Minecraft `1.21.9`, Yarn `1.21.9+build.1`, Fabric Loader `0.18.4`, and Fabric API `0.134.1+1.21.9`. Only its build tooling was updated to Loom `1.17.20` and Gradle `9.5.1`. It continued using the old `net.fabricmc.fabric-loom-remap` plugin and Yarn dependency during this step.

To repeat the conversion independently:

```sh
export JAVA_HOME="/path/to/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

git clone https://github.com/hhhzzzsss/SongPlayer.git SongPlayer-migration
cd SongPlayer-migration
git checkout 36f9d1f3dda4cd7b5728e6e4fb54e0a95ff9c9d8
```

Set `loom_version=1.17.20` in `gradle.properties` and change the wrapper's distribution URL to `https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip`. Then run the command used for this port:

```sh
./gradlew migrateMappings --mappings 'net.minecraft:mappings:1.21.9' --no-daemon
```

The successful migration wrote Java sources to `remappedSrc/` and left the input sources intact. Those files were copied into the port's `src/main/java/` before compiling against 26.2. This converted classes, fields, methods, and most Mixin descriptors together. Remaining wildcard imports and API changes were corrected manually.

## 2. Update the target build

The final settings are in [gradle.properties](../gradle.properties), [build.gradle](../build.gradle), and the [Gradle wrapper configuration](../gradle/wrapper/gradle-wrapper.properties).

| Setting | Upstream 1.21.9 | This 26.2 port |
| --- | --- | --- |
| Minecraft | `1.21.9` | `26.2` |
| Java release / source / target | `21` | `25` |
| Gradle | `9.2.1` | `9.5.1` |
| Loom | `1.14-SNAPSHOT` | `1.17.20` |
| Loom plugin | `net.fabricmc.fabric-loom-remap` | `net.fabricmc.fabric-loom` |
| Mappings | Yarn `1.21.9+build.1:v2` | No mappings dependency |
| Fabric Loader | `0.18.4` | `0.19.3` |
| Fabric API | `0.134.1+1.21.9` | `0.160.0+26.2` |
| Dependency configuration | `modImplementation` | `implementation` |
| Mod version | `3.3.5` | `3.3.5+26.2` |

The unobfuscated Loom plugin produces the runtime JAR directly. [Fabric's 26.1 build migration instructions](https://docs.fabricmc.net/26.1.2/develop/porting/) explain the plugin change, removal of mappings, ordinary dependency configurations, and Java 25 requirement; [the 26.2 porting guide](https://docs.fabricmc.net/develop/porting/) covers updating the target dependencies.

[fabric.mod.json](../src/main/resources/fabric.mod.json) now declares a client mod, Minecraft `~26.2`, Java `>=25`, Loader `>=0.19.3`, and Fabric API `>=0.160.0`. The Mixin compatibility level is `JAVA_25`.

## 3. Adapt Minecraft APIs and Mixin targets

The converted sources were checked against the actual 26.2 Minecraft classes and method descriptors, including bytecode inspection for Mixin injection points. Representative changes:

| Previous API or target | 26.2 equivalent |
| --- | --- |
| `MinecraftClient.render(boolean)` | `Minecraft.runTick(boolean)`; retains the per-frame playback update |
| `MinecraftClient.doItemUse()` | `Minecraft.startUseItem()`; retains right-click song-item interception |
| `MinecraftClient.setScreen(...)` | `Minecraft.gui.setScreen(...)` |
| `ClientPlayNetworkHandler.sendChatMessage(...)` | `ClientPacketListener.sendChat(...)`; cancels local SongPlayer commands |
| `onGameJoin`, `onPlayerRespawn`, `onPlayerPositionLook` | `handleLogin`, `handleRespawn`, `handleMovePlayer`, with official packet descriptors |
| `onPlayerAbilities`, `onEntityVelocityUpdate` | `handlePlayerAbilities`, `handleSetEntityMotion`; velocity target is `Entity.lerpMotion(Vec3)` |
| `ClientCommonNetworkHandler.sendPacket(...)` | `ClientCommonPacketListenerImpl.send(Packet)`; retains stage movement interception |
| `ClientPlayerEntity.tickMovement()` / `allowFlying` | `LocalPlayer.aiStep()` / `Abilities.mayfly` |
| `PlayerEntity.tick()` / `noClip` | `Player.tick()` / `noPhysics` |
| `ClientWorld.handleBlockUpdate(...)` | `ClientLevel.setServerVerifiedBlockState(...)` |
| `ChatInputSuggestor.refresh()` | `CommandSuggestions.updateCommandInfo()`; retains the native suggestion popup |
| `syncSelectedSlot()` invoker | `MultiPlayerGameMode.ensureHasSentCarriedItem()` |
| Player-list map accessor | `ClientPacketListener.playerInfoMap` |
| `InGameHud.renderHeldItemTooltip(DrawContext)` | `Hud.extractSelectedItemName(GuiGraphicsExtractor)` |
| `Screen.render(...)` | `Screen.extractRenderState(...)` |
| `Text`, `Formatting`, `GameMode` | `Component`, `ChatFormatting`, `GameType` |
| Dimension key `getValue()` | `ResourceKey.identifier()` |
| Local player `sendMessage(text, false)` | `Minecraft.gui.hud.getChat().addClientSystemMessage(text)` |

The HUD moved out of `Gui` into `Hud`. Its progress overlay uses `GuiGraphicsExtractor.text`, preserving text shadows and fade alpha. The selected-item extraction method has one shared return, so its tail injection still runs when the hand is empty or the highlight timer is zero.

[SongItemConfirmationScreen](../src/main/java/com/github/hhhzzzsss/songplayer/item/SongItemConfirmationScreen.java) renders through extraction, checks background loading completion in `tick()`, and restores its Play/Cancel buttons when the screen is reinitialized. Its title uses opaque ARGB color. `MultiLineLabel.visitLines` supplies the centered song statistics to the new text renderer.

Creative inventory updates still send `handleCreativeModeItemAdd`, and interaction still uses `useItemOn` / `startDestroyBlock`. Cleanup now reads the stream returned by `BlockState.getValues()` and copies each property's name and serialized value into `BlockItemStateProperties`.

The relative-teleport recovery path also fixes the upstream X-coordinate typo: relative X now adds `dx`, rather than `dz`, to the stage origin.

## 4. Use semantic note IDs

Upstream inferred a note from its raw block-state registry ID and a contiguous range of 800 states. That assumption is fragile when Minecraft adds note-block instruments or changes state ordering.

[NoteBlockNotes](../src/main/java/com/github/hhhzzzsss/songplayer/playing/NoteBlockNotes.java) instead checks that the block is a note block, reads `NoteBlock.INSTRUMENT` and `NoteBlock.NOTE`, and matches the instrument's serialized name to SongPlayer's existing sixteen-instrument enum:

```text
songNoteId = songInstrumentId * 25 + pitch
```

This preserves IDs `0..399`, including the original instrument order. Powered and unpowered states produce the same song ID. Non-note blocks and instruments outside the existing song format return `-1`. This helper is used for creative stage discovery, construction, survival discovery, and altered-stage checks.

## 5. Fix issues exposed by running the game

Compilation alone did not catch two runtime failures.

**Fake-player entity ID:** the first client test crashed with `IllegalStateException: Tried to access entity ID before ID assignment`. In 26.2 the fake `RemotePlayer` needs an explicit ID before it is added to the level. [FakePlayerEntity](../src/main/java/com/github/hhhzzzsss/songplayer/FakePlayerEntity.java) now chooses an unused negative ID and calls `setId` before `world.addEntity(this)`. Existing entities, including another mod's client entities, are checked before selecting an ID.

**Cleanup mode race:** another run reached cleanup but stalled in survival mode. A survival request sent at the end of building could be acknowledged after cleanup started. [SongHandler.handleCleanup](../src/main/java/com/github/hhhzzzsss/songplayer/playing/SongHandler.java) now calls `setCreativeIfNeeded()` each cleanup tick, using the existing command cooldown. That helper clears the queued command first and only queues a creative request when the client is still in another mode. Once creative mode is acknowledged, the pending retry is cleared so it cannot switch a later survival playback session back to creative.

## 6. Build and reproduce verification

From a checkout of this fork, use a full Java 25 JDK:

```sh
export JAVA_HOME="/path/to/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew clean build
./gradlew runClientGameTest
```

`build` runs the seven JUnit tests and produces `build/libs/song-player-3.3.5+26.2.jar` plus the separate sources JAR. Unit-test results are available in `build/reports/tests/test/index.html` and `build/test-results/test/`.

The verified unit run reported **7 tests, 0 failures, 0 errors, 0 skipped**:

| Test source | Contracts checked |
| --- | --- |
| [TxtConverterTest](../src/test/java/com/github/hhhzzzsss/songplayer/conversion/TxtConverterTest.java), 2 tests | Comments and CRLF input; highest supported instrument; malformed input reports the actual source line |
| [SongTest](../src/test/java/com/github/hhhzzzsss/songplayer/song/SongTest.java), 5 tests | Chord ordering and required notes; seek boundaries; finite loops with elapsed remainder; infinite loops and reset; repeated `play()` preserves the start time |

[SongPlayerGameTest](../src/gametest/java/com/github/hhhzzzsss/songplayer/test/SongPlayerGameTest.java) launches the real Fabric client and creates a disposable integrated-server world. Its probes observe outgoing chat and received server note packets. The fixture verifies:

- All 800 combinations of sixteen instruments, 25 pitches, and both powered states; unsupported instruments and ordinary blocks are excluded.
- `$help`, `$songs`, and `$status` stay local; ordinary chat is sent normally; native autocomplete opens and Tab applies `$status`.
- SP song and loop metadata, velocity filtering, item NBT codec round-trips, unrelated custom data, custom names, lore, and song-item loading statistics.
- Song-item confirmation Cancel and Play actions.
- Creative stage construction, survival playback, and real server note events for every note in the fixture song.
- Fake-player attachment, rebuilding a changed stage, automatic cleanup, original block states, hotbar contents, and original creative mode restoration.
- Survival-only tuning and playback, plus playback reset after disconnect.

The final client run completed successfully and printed:

```text
SONGPLAYER_26_2_GAMETEST_PASS: 800 semantic note states; new instruments excluded; chat command interception and native autocomplete; SP format and NBT item persistence; confirmation Cancel/Play; creative stage construction; server note events; fake player; altered-stage rebuild; inventory and block cleanup; survival tuning
```

The test configuration accepts the Minecraft EULA for its disposable test run and captures screenshots of autocomplete, confirmation, the playing stage, and restored terrain. The test source set and its probe Mixins are separate from the distributed mod JAR.

This validation uses an integrated server. A particular multiplayer server's permissions, custom gamemode commands, packet restrictions, and interaction rules still determine which SongPlayer features can run there.
