# Movement fix for 26.3 servers through ViaFabricPlus

SongPlayer `3.3.5+26.2.1` changes how it sends stage movement while building, playing, and cleaning up. It targets a Minecraft **26.2 client** connecting to a **26.3 server** through **ViaFabricPlus 5.0.2**. The reported disconnect happened as note-block construction began, with the translation key `multiplayer.disconnect.invalid_player_movement`.

The behavior below is supported by the published server bytecode and SongPlayer's packet paths, and was reproduced against a disposable native 26.3 loopback server. The final Loader 0.19.5 build, all 15 unit tests, the native 26.2 client test, and the ViaFabricPlus network test passed.

## What changed in the server

We inspected Mojang's [official 26.3 server JAR](https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar), SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`, using `javap -c -p` on `net.minecraft.server.network.ServerGamePacketListenerImpl`.

In 26.3, `handleMovePlayer` checks `ServerboundMovePlayerPacket.hasPosition()`. If `receivedPositionThisTick` is already true, it disconnects with `multiplayer.disconnect.invalid_player_movement`; otherwise, it sets that flag. `handleClientTickEnd` clears it. The corresponding extra-position guard is absent from the published 26.2 server.

The restriction is therefore **one position-bearing movement packet between client tick-end packets**. Rotation-only movement packets do not set that flag. This is separate from the existing rejection of non-finite movement values; the disconnect text alone cannot distinguish those branches.

The old SongPlayer paths could violate the new rule in two ways:

- With rotation disabled, the movement Mixin replaced every vanilla movement packet with a full stage `PosRot`, even when the original packet carried only rotation or ground status.
- Stage movement and per-block aiming also sent full `PosRot` packets directly through `Connection.send`, bypassing that Mixin. Multiple block actions could therefore send multiple positions in the same client tick.

That behavior was accepted by the native 26.2 server used for the original port's integrated-world tests. A build or a native 26.2 test cannot establish compatibility with the extra 26.3 check.

## How the fix works

[StageMovementPackets](../src/main/java/com/github/hhhzzzsss/songplayer/playing/StageMovementPackets.java) now handles all custom stage movement:

1. When no position has been sent in the current client tick, a stage update sends the stage position and look as `PosRot`.
2. Later stage updates send `Rot`, preserving per-block aiming without repeating the position.
3. [ConnectionMixin](../src/main/java/com/github/hhhzzzsss/songplayer/mixin/ConnectionMixin.java) observes outgoing movement and `ServerboundClientTickEndPacket`, including ordinary vanilla packets. A vanilla position sent before playback begins uses the same allowance; the actual tick-end packet resets it.
4. A new connection resets the helper's state. Invalid yaw or pitch falls back to the last finite value for that axis, initially zero.

[Stage](../src/main/java/com/github/hhhzzzsss/songplayer/playing/Stage.java), [SongHandler](../src/main/java/com/github/hhhzzzsss/songplayer/playing/SongHandler.java), and [ClientCommonNetworkHandlerMixin](../src/main/java/com/github/hhhzzzsss/songplayer/mixin/ClientCommonNetworkHandlerMixin.java) all use that helper. SongPlayer continues to hold the server position at the stage while allowing its local camera behavior and block rotations.

The finite-angle fallback is an additional safeguard. It is not evidence that NaN or infinity caused the reported construction disconnect.

## Preserve teleport acknowledgements

26.3 also changed `ServerboundAcceptTeleportationPacket` to carry position and rotation, as documented in the [NeoForged 26.3 migration primer](https://docs.neoforged.net/primer/docs/26.3/).

For the older 26.2 client, the released [ViaBackwards 5.12.0 conversion](https://github.com/ViaVersion/ViaBackwards/blob/5.12.0/common/src/main/java/com/viaversion/viabackwards/protocol/v26_3to26_2/rewriter/EntityPacketRewriter26_3.java#L158-L179) stores the ID-only acknowledgement, cancels that packet, and converts the next `MOVE_PLAYER_POS_ROT` into the full 26.3 acknowledgement. It then clears the stored ID. Suppressing or rewriting that vanilla follow-up can interfere with this conversion.

[ClientPlayNetworkHandlerMixin](../src/main/java/com/github/hhhzzzsss/songplayer/mixin/ClientPlayNetworkHandlerMixin.java) now marks the actual client-thread handling of a server position correction. During that handling, the movement interception lets vanilla's acknowledgement and position follow-up pass unchanged. It clears the guard before SongPlayer's stage recovery runs. The helper conservatively observes that follow-up as a used position allowance for the rest of the client tick.

The native 26.3 acknowledgement handler calls `handlePlayerPositionChange` directly. It does **not** set `receivedPositionThisTick`; the flag belongs to `handleMovePlayer`. The unchanged acknowledgement path and the limit on custom stage positions address different parts of the protocol.

## Loader requirement

This release requires **Fabric Loader 0.19.5 or newer**, both in [the build settings](../gradle.properties) and [the mod metadata](../src/main/resources/fabric.mod.json).

The opt-in development runtime exposed a separate startup failure in ViaFabricPlus 5.0.2's array `@Redirect` with the older MixinExtras bundled by Loader 0.19.3. Fabric's published settings show [Loader 0.19.3 using MixinExtras 0.5.4](https://github.com/FabricMC/fabric-loader/blob/0.19.3/gradle.properties#L13) and [Loader 0.19.5 using MixinExtras 0.5.5](https://github.com/FabricMC/fabric-loader/blob/0.19.5/gradle.properties#L13). The supported release now uses Loader 0.19.5 so the reproducible test runtime includes the newer version.

This startup compatibility change is separate from the server's extra-position disconnect. Update Loader when installing the new SongPlayer JAR.

## Build and native verification

Use a full Java 25 JDK:

```sh
export JAVA_HOME="/path/to/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build
./gradlew runClientGameTest
```

The runtime artifact is `build/libs/song-player-3.3.5+26.2.1.jar`. The build's 15 unit tests include five movement tests covering repeated stage updates, an earlier vanilla position, rotation-only packets, reconnects, and the teleport guard with finite-angle fallback. Results are in `build/reports/tests/test/index.html`.

The default client game test uses a disposable native 26.2 integrated world and checks construction, playback, rebuilding, cleanup, inventory restoration, and survival tuning. Its successful completion marker is `SONGPLAYER_26_2_GAMETEST_PASS`.

The final Loader 0.19.5 native run completed successfully in 26 seconds. It checked all 800 semantic note states, local commands and native autocomplete, song and item persistence, confirmation actions, creative construction, real note events, the fake player, rebuilding, block and inventory cleanup, survival tuning, and disconnect reset.

## Opt-in 26.2 → 26.3 network verification

Use [ViaFabricPlus 5.0.2](https://github.com/ViaVersion/ViaFabricPlus/releases/tag/5.0.2), whose release added 26.3-server support while retaining the 26.2 client. Its [build dependencies](https://github.com/ViaVersion/ViaFabricPlus/blob/94880c8cb7a344bbbf7b1c2e1988b1bd6626b83c/viafabricplus-api/build.gradle.kts#L20-L24) include ViaVersion and ViaBackwards 5.12.0. The release JAR includes those dependencies. ViaFabricPlus 5.1.x is for a 26.3 client.

Start a separate, disposable **native Minecraft 26.3 server** bound to loopback, for example `127.0.0.1:25567`. Configure `server-ip=127.0.0.1`, `server-port=25567`, `online-mode=false`, `enforce-secure-profile=false`, `white-list=false`, `gamemode=creative`, and `spawn-protection=0`. These offline and allowlist settings are for this loopback-only fixture. The explicit `white-list=false` is necessary because the 26.3 server can default to an enabled allowlist in offline mode. Accept the Minecraft EULA in that test directory and give the test client operator permissions from the server console. The fixture clears and rebuilds the small arena around `(0, 70, 0)` and uses gamemode, fill, time, and teleport commands, so it must have its own disposable world.

With that server running, execute:

```sh
./gradlew runClientGameTest \
  -PsongplayerViaTestServer=127.0.0.1:25567 \
  -PviaFabricPlusJar=/path/to/ViaFabricPlus-5.0.2.jar
```

This opt-in command selects [SongPlayerViaGameTest](../src/gametest/java/com/github/hhhzzzsss/songplayer/test/SongPlayerViaGameTest.java), loads the specified ViaFabricPlus JAR, and selects its 26.3 target. It accepts only an explicit loopback endpoint. Without the properties, the ordinary native 26.2 test runs.

The network fixture first sends two position packets in one client tick while SongPlayer is idle and requires the exact native invalid-movement disconnect. After reconnecting, it checks that one position followed by multiple rotation-only packets stays connected. It then exercises stage construction and real server note events with rotation disabled and enabled, a server teleport during construction, fake-player attachment, automatic cleanup, original block and hotbar restoration, and stopping during construction.

The expected final marker is `SONGPLAYER_VIA_26_3_GAMETEST_PASS`. A marker is a verification result only when the run actually completes successfully. Check the client output and the disposable server log together; the deliberately rejected baseline connection is expected.

## Verified cross-version result

On October 4, 2026, the fixture completed successfully in 37 seconds with Minecraft 26.2, Fabric Loader 0.19.5, MixinExtras 0.5.5, and ViaFabricPlus 5.0.2 targeting protocol `26.3 (777)` on the disposable native server. It verified:

- Two ordinary position packets before the next tick end produce `Invalid move player packet received` with the expected translation key.
- One position followed by multiple rotation-only packets stays connected.
- Rotation disabled and enabled both complete construction and receive real server note events for every fixture note.
- Server teleports during construction retain the vanilla acknowledgement path.
- The fake player remains attached; automatic cleanup restores the original blocks, hotbar, and game mode.
- Stopping during construction also completes cleanup.

The completed run printed:

```text
SONGPLAYER_VIA_STRICT_BASELINE_PASS: Connection Lost. Invalid move player packet received
SONGPLAYER_VIA_ROTATION_CONTROL_PASS
SONGPLAYER_VIA_STOP_DURING_BUILD_PASS
SONGPLAYER_VIA_26_3_GAMETEST_PASS: Minecraft 26.2 + ViaFabricPlus target26.3; native strict duplicate-position disconnect reproduced; one position plus rotation-only control; rotate=false/true construction and real server note playback; fake player; vanilla teleport ACKs during building; automatic cleanup and original block/hotbar restoration; stop during building
```

**Final verification status:** the Loader 0.19.5 build passed; all 15 unit tests passed with zero failures, errors, or skips; the native 26.2 client test passed; and the ViaFabricPlus test against the disposable native 26.3 loopback server passed.
