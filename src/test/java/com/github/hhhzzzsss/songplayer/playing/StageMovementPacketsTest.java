package com.github.hhhzzzsss.songplayer.playing;

import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
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
}
