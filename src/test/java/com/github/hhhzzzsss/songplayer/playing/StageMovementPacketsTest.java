package com.github.hhhzzzsss.songplayer.playing;

import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StageMovementPacketsTest {
    private static final BlockPos STAGE = new BlockPos(12, 70, -8);

    @Test
    void manyStageActionsSendOnlyOnePositionUntilClientTickEnd() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        int positions = 0;
        for (int i = 0; i < 40; i++) {
            var packet = StageMovementPackets.createStagePacket(connection, STAGE, i, -30);
            if (packet.hasPosition()) positions++;
            assertEquals(i, packet.getYRot(0));
            StageMovementPackets.observeOutgoing(connection, packet);
        }
        assertEquals(1, positions, "26.3 rejects a second position in a client tick");
        StageMovementPackets.observeOutgoing(connection, ServerboundClientTickEndPacket.INSTANCE);
        var next = StageMovementPackets.createStagePacket(connection, STAGE, 0, 0);
        assertTrue(next.hasPosition());
        assertEquals(12.5, next.getX(0));
        assertEquals(70, next.getY(0));
        assertEquals(-7.5, next.getZ(0));
    }

    @Test
    void vanillaPositionBeforeSongStartsConsumesTheSameBudget() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        StageMovementPackets.observeOutgoing(connection,
                new ServerboundMovePlayerPacket.Pos(12, 70, -8, true, false));
        assertFalse(StageMovementPackets.createStagePacket(connection, STAGE, 20, -10).hasPosition());
        StageMovementPackets.observeOutgoing(connection, ServerboundClientTickEndPacket.INSTANCE);
        assertTrue(StageMovementPackets.createStagePacket(connection, STAGE, 20, -10).hasPosition());
    }

    @Test
    void rotationOnlyPacketsDoNotConsumeThePositionBudget() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        StageMovementPackets.observeOutgoing(connection, new ServerboundMovePlayerPacket.Rot(45, 10, true, false));
        assertTrue(StageMovementPackets.createStagePacket(connection, STAGE, 45, 10).hasPosition());
    }

    @Test
    void reconnectStartsWithANewBudgetAndNoTeleportGuard() {
        Connection old = new Connection(PacketFlow.CLIENTBOUND);
        StageMovementPackets.createStagePacket(old, STAGE, 0, 0);
        StageMovementPackets.beginServerPositionCorrection(old);
        Connection next = new Connection(PacketFlow.CLIENTBOUND);
        assertTrue(StageMovementPackets.createStagePacket(next, STAGE, 0, 0).hasPosition());
        assertFalse(StageMovementPackets.isHandlingServerPositionCorrection(next));
    }

    @Test
    void teleportGuardPreservesBudgetAndNonfiniteAnglesUseLastValidLook() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        StageMovementPackets.observeOutgoing(connection,
                new ServerboundMovePlayerPacket.PosRot(12, 70, -8, 60, -25, true, false));
        StageMovementPackets.beginServerPositionCorrection(connection);
        assertTrue(StageMovementPackets.isHandlingServerPositionCorrection(connection));
        StageMovementPackets.endServerPositionCorrection(connection);
        var packet = StageMovementPackets.createStagePacket(connection, STAGE, Float.NaN, Float.POSITIVE_INFINITY);
        assertFalse(packet.hasPosition());
        assertEquals(60, packet.getYRot(0));
        assertEquals(-25, packet.getXRot(0));
        assertTrue(Float.isFinite(packet.getYRot(0)) && Float.isFinite(packet.getXRot(0)));
    }

    @Test
    void stopHandoffPreservesVanillaLookAndFlagsWithoutASecondPosition() {
        CapturingConnection connection = new CapturingConnection();
        StageMovementPackets.sendStageMovement(connection, STAGE, 10, -20);
        assertTrue(((ServerboundMovePlayerPacket) connection.sent).hasPosition());
        var vanilla = new ServerboundMovePlayerPacket.PosRot(13, 70, -7, 91, -37, false, true);
        var rewritten = (ServerboundMovePlayerPacket) StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true);
        assertInstanceOf(ServerboundMovePlayerPacket.Rot.class, rewritten);
        assertFalse(rewritten.hasPosition());
        assertEquals(91, rewritten.getYRot(0));
        assertEquals(-37, rewritten.getXRot(0));
        assertFalse(rewritten.isOnGround());
        assertTrue(rewritten.horizontalCollision());
    }

    @Test
    void stopHandoffPreservesPositionOnlyPacketsGroundAndCollisionStatus() {
        CapturingConnection connection = new CapturingConnection();
        StageMovementPackets.sendStageMovement(connection, STAGE, 0, 0);
        for (boolean ground : new boolean[]{false, true}) {
            for (boolean collision : new boolean[]{false, true}) {
                var vanilla = new ServerboundMovePlayerPacket.Pos(13, 70, -7, ground, collision);
                var rewritten = (ServerboundMovePlayerPacket) StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true);
                assertInstanceOf(ServerboundMovePlayerPacket.StatusOnly.class, rewritten);
                assertFalse(rewritten.hasPosition());
                assertFalse(rewritten.hasRotation());
                assertEquals(ground, rewritten.isOnGround());
                assertEquals(collision, rewritten.horizontalCollision());
            }
        }
    }

    @Test
    void ordinaryIdleMovementAndStageReservationDoNotArmHandoffGuard() {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        var vanilla = new ServerboundMovePlayerPacket.PosRot(13, 70, -7, 91, -37, false, true);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true));
        StageMovementPackets.observeOutgoing(connection, vanilla);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true));

        Connection reserved = new Connection(PacketFlow.CLIENTBOUND);
        assertTrue(StageMovementPackets.createStagePacket(reserved, STAGE, 0, 0).hasPosition());
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(reserved, vanilla, true),
                "Creating an unsent stage packet must not rewrite ordinary vanilla movement");
    }

    @Test
    void rotationOnlyStageEmissionAfterVanillaPositionDoesNotArmHandoffGuard() {
        CapturingConnection connection = new CapturingConnection();
        var vanilla = new ServerboundMovePlayerPacket.Pos(13, 70, -7, true, false);
        StageMovementPackets.observeOutgoing(connection, vanilla);
        StageMovementPackets.sendStageMovement(connection, STAGE, 45, 10);
        assertFalse(((ServerboundMovePlayerPacket) connection.sent).hasPosition());
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true));
    }

    @Test
    void tickEndAndReconnectRestoreUnmodifiedIdleMovement() {
        CapturingConnection connection = new CapturingConnection();
        var vanilla = new ServerboundMovePlayerPacket.Pos(13, 70, -7, true, false);
        StageMovementPackets.sendStageMovement(connection, STAGE, 0, 0);
        assertNotSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true));
        StageMovementPackets.observeOutgoing(connection, ServerboundClientTickEndPacket.INSTANCE);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true));

        StageMovementPackets.sendStageMovement(connection, STAGE, 0, 0);
        Connection next = new Connection(PacketFlow.CLIENTBOUND);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(next, vanilla, true));
    }

    @Test
    void activeSongAndVanillaTeleportCorrectionPassThroughHandoffGuard() {
        CapturingConnection connection = new CapturingConnection();
        var vanilla = new ServerboundMovePlayerPacket.PosRot(13, 70, -7, 91, -37, false, true);
        StageMovementPackets.sendStageMovement(connection, STAGE, 0, 0);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, false));
        StageMovementPackets.beginServerPositionCorrection(connection);
        assertSame(vanilla, StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true),
                "Vanilla server teleport acknowledgement follow-ups must retain their positions");
        StageMovementPackets.endServerPositionCorrection(connection);
        assertFalse(((ServerboundMovePlayerPacket) StageMovementPackets.rewriteAfterStageHandoff(connection, vanilla, true)).hasPosition());
    }

    private static final class CapturingConnection extends Connection {
        private Packet<?> sent;

        private CapturingConnection() { super(PacketFlow.CLIENTBOUND); }

        @Override
        public void send(Packet<?> packet) {
            sent = packet;
            StageMovementPackets.observeOutgoing(this, packet);
        }
    }
}
