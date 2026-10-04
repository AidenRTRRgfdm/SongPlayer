# Movement fix for 26.3 servers through ViaFabricPlus

SongPlayer `3.3.5+26.2.2` handles stage movement and the return to normal movement after stopping. It targets a Minecraft **26.2 client** connecting to a **26.3 server** through **ViaFabricPlus 5.0.2**. The initial report disconnected as note-block construction began, with the translation key `multiplayer.disconnect.invalid_player_movement`. A later report exposed a separate handoff after `$stop` with automatic cleanup disabled.

The server restriction is supported by the published bytecode and was reproduced against a disposable native 26.3 loopback server. The previous `3.3.5+26.2.1` passed its original fixture but failed the added direct-stop regression. The patched `3.3.5+26.2.2` build, all 21 unit tests, native 26.2 client rerun, and expanded ViaFabricPlus fixture passed.

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

## Stopping without cleanup: the additional 26.2.2 fix

The first fix governed custom stage packets while SongPlayer was active. With `autoCleanup=false`, a real `$stop` command can reset the handler to idle in the same client tick after a stage position was sent. The normal player can then send a vanilla `Pos` or `PosRot` through the now-idle movement path. That is another position before `ClientTickEnd`, so it violates the same 26.3 restriction. Interrupting cleanup with a second `$stop` has the same handoff.

The connection-level filter in `3.3.5+26.2.2` applies only when SongPlayer is idle and a custom stage position was actually emitted earlier in the current client tick. It removes the repeated position component, retaining rotation when present and preserving the packet's ground and horizontal-collision flags. A `PosRot` becomes `Rot`; a position-only packet becomes `StatusOnly`.

This narrow handoff ends at the next actual client tick-end packet. A new connection clears its state, and the server-position-correction guard still allows the vanilla teleport acknowledgement follow-up. Ordinary idle movement without an earlier stage position is unaffected.

The earlier protocol fixture stopped during construction with cleanup enabled and then waited for restoration. It did not force normal player movement in the same tick after a direct playback stop. The expanded fixture now plays real notes, emits a stage position, invokes the actual `$stop` command, and sends vanilla movement in one client runnable, so no tick-end can intervene. It covers cleanup disabled with rotation off and on, and interruption of cleanup with a second stop, then checks manual restoration of the retained stage.

The old `3.3.5+26.2.1` implementation was compiled and run with the added regression against the same disposable native 26.3 server. Playback reached the stage successfully; after the real stop with cleanup disabled, the client disconnected with `Invalid move player packet received`, and the fixture failed its same-tick movement assertion. The server log recorded the same rejection. The patched `3.3.5+26.2.2` passed that handoff and the other stop variants in the expanded fixture.

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

The runtime artifact is `build/libs/song-player-3.3.5+26.2.2.jar`. The build passed all **21 unit tests**, with zero failures, errors, or skips. Results are in `build/reports/tests/test/index.html`. Eleven movement tests cover the original position limit and the additional stop handoff: preserving look and flags, arming only after an emitted stage position, restoring ordinary movement at tick end or reconnect, and allowing active-song and teleport-correction packets through.

The default client game test uses a disposable native 26.2 integrated world and checks construction, playback, rebuilding, cleanup, inventory restoration, and survival tuning. Its successful completion marker is `SONGPLAYER_26_2_GAMETEST_PASS`.

The final `3.3.5+26.2.2` Loader 0.19.5 native run completed successfully in **34 seconds**. It checked all 800 semantic note states, local commands and native autocomplete, song and item persistence, confirmation actions, creative construction, real note events, the fake player, rebuilding, block and inventory cleanup, survival tuning, and disconnect reset. It printed `SONGPLAYER_26_2_GAMETEST_PASS`.

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

The network fixture first sends two position packets in one client tick while SongPlayer is idle and requires the exact native invalid-movement disconnect. After reconnecting, it checks that one position followed by multiple rotation-only packets stays connected. The expanded fixture then forces the stop handoffs described above, followed by stage construction and real server note events with rotation disabled and enabled, a server teleport during construction, fake-player attachment, automatic cleanup, original block and hotbar restoration, and stopping during construction.

The expected final marker is `SONGPLAYER_VIA_26_3_GAMETEST_PASS`. A marker is a verification result only when the run actually completes successfully. Check the client output and the disposable server log together; the deliberately rejected baseline connection is expected.

## Previously verified 26.2.1 cross-version result

On October 4, 2026, the `3.3.5+26.2.1` fixture completed successfully in 37 seconds with Minecraft 26.2, Fabric Loader 0.19.5, MixinExtras 0.5.5, and ViaFabricPlus 5.0.2 targeting protocol `26.3 (777)` on the disposable native server. It verified:

- Two ordinary position packets before the next tick end produce `Invalid move player packet received` with the expected translation key.
- One position followed by multiple rotation-only packets stays connected.
- Rotation disabled and enabled both complete construction and receive real server note events for every fixture note.
- Server teleports during construction retain the vanilla acknowledgement path.
- The fake player remains attached; automatic cleanup restores the original blocks, hotbar, and game mode.
- Stopping during construction with automatic cleanup enabled also completes cleanup.

The completed run printed:

```text
SONGPLAYER_VIA_STRICT_BASELINE_PASS: Connection Lost. Invalid move player packet received
SONGPLAYER_VIA_ROTATION_CONTROL_PASS
SONGPLAYER_VIA_STOP_DURING_BUILD_PASS
SONGPLAYER_VIA_26_3_GAMETEST_PASS: Minecraft 26.2 + ViaFabricPlus target26.3; native strict duplicate-position disconnect reproduced; one position plus rotation-only control; rotate=false/true construction and real server note playback; fake player; vanilla teleport ACKs during building; automatic cleanup and original block/hotbar restoration; stop during building
```

These are historical results for `3.3.5+26.2.1`; they do not verify the additional direct stop handoff.

## Verified 26.2.2 stop-handoff result

The patched `3.3.5+26.2.2` expanded run completed successfully in **1 minute 13 seconds**, against the same disposable native 26.3 server with Minecraft 26.2, Loader 0.19.5, and ViaFabricPlus 5.0.2. The three new handoff cases all stayed connected:

- Automatic cleanup disabled, rotation disabled.
- Automatic cleanup disabled, rotation enabled.
- Automatic cleanup enabled, followed by a second `$stop` that interrupts cleanup.

Each case played actual server notes, invoked the real command, and sent vanilla movement before the next tick-end packet. It verified idle state, fake-player removal, preserved hotbar contents, and manual cleanup of the retained stage. The same run also passed the strict duplicate-position baseline, rotation-only control, both rotation settings for full construction and playback, server teleport acknowledgements, automatic restoration, and stopping during construction.

The completed run printed:

```text
SONGPLAYER_VIA_STOP_HANDOFF_PASS: autoCleanup=false rotate=false
SONGPLAYER_VIA_STOP_HANDOFF_PASS: autoCleanup=false rotate=true
SONGPLAYER_VIA_STOP_HANDOFF_PASS: autoCleanup=true rotate=true
SONGPLAYER_VIA_26_3_GAMETEST_PASS: Minecraft 26.2 + ViaFabricPlus target26.3; native strict duplicate-position disconnect reproduced; one position plus rotation-only control; rotate=false/true construction and real server note playback; fake player; vanilla teleport ACKs during building; automatic cleanup and original block/hotbar restoration; stop during building; same-client-tick vanilla movement after stopping playback and aborting cleanup
```

**Final 26.2.2 verification status:** the old-code stop regression reproduced the exact invalid-movement disconnect. The patched build and all 21 unit tests passed with zero failures, errors, or skips; the native 26.2 client rerun passed in 34 seconds; and the expanded ViaFabricPlus protocol run passed in 1 minute 13 seconds against the disposable native 26.3 server. The external server from the original report has not been retested.
