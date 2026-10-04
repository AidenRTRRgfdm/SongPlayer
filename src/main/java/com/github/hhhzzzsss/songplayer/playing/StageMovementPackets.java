package com.github.hhhzzzsss.songplayer.playing;

import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/** Keeps stage movement within the one-position-update-per-client-tick protocol contract. */
public final class StageMovementPackets {
    private static Connection currentConnection;
    private static boolean positionSent;
    private static boolean stagePositionSent;
    private static boolean handlingServerPositionCorrection;
    private static float lastYaw;
    private static float lastPitch;

    private StageMovementPackets() {}

    private static void useConnection(Connection connection) {
        if (currentConnection != connection) {
            currentConnection = connection;
            positionSent = false;
            stagePositionSent = false;
            handlingServerPositionCorrection = false;
            lastYaw = 0;
            lastPitch = 0;
        }
    }

    /** Observe vanilla packets as well as SongPlayer packets on the main client connection. */
    public static synchronized void observeOutgoing(Connection connection, Packet<?> packet) {
        if (!(packet instanceof ServerboundMovePlayerPacket)
                && !(packet instanceof ServerboundClientTickEndPacket)) return;
        useConnection(connection);
        if (packet instanceof ServerboundClientTickEndPacket) {
            positionSent = false;
            stagePositionSent = false;
        } else if (packet instanceof ServerboundMovePlayerPacket movement) {
            // Count teleport acknowledgement follow-ups conservatively too. ViaFabricPlus
            // may translate them into a separate acknowledgement packet on newer servers.
            if (movement.hasPosition()) positionSent = true;
            if (movement.hasRotation()) {
                float yaw = movement.getYRot(lastYaw);
                float pitch = movement.getXRot(lastPitch);
                if (Float.isFinite(yaw)) lastYaw = yaw;
                if (Float.isFinite(pitch)) lastPitch = pitch;
            }
        }
    }

    /** Create and reserve the next stage update without changing the local camera or position. */
    public static synchronized ServerboundMovePlayerPacket createStagePacket(
            Connection connection, BlockPos position, float yaw, float pitch) {
        useConnection(connection);
        lastYaw = Float.isFinite(yaw) ? yaw : lastYaw;
        lastPitch = Float.isFinite(pitch) ? pitch : lastPitch;
        if (positionSent) {
            return new ServerboundMovePlayerPacket.Rot(lastYaw, lastPitch, true, false);
        }
        positionSent = true;
        return new ServerboundMovePlayerPacket.PosRot(
                position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                lastYaw, lastPitch, true, false);
    }

    public static synchronized void sendStageMovement(
            Connection connection, BlockPos position, float yaw, float pitch) {
        ServerboundMovePlayerPacket packet = createStagePacket(connection, position, yaw, pitch);
        connection.send(packet);
        if (packet.hasPosition()) stagePositionSent = true;
    }

    /** Leave ordinary idle movement alone, except when returning control after a stage update. */
    public static synchronized Packet<?> rewriteAfterStageHandoff(
            Connection connection, Packet<?> packet, boolean songIdle) {
        useConnection(connection);
        if (!songIdle || !stagePositionSent || !positionSent || handlingServerPositionCorrection
                || !(packet instanceof ServerboundMovePlayerPacket movement) || !movement.hasPosition()) {
            return packet;
        }
        // $stop can return control to vanilla before this tick's stage position has
        // reached CLIENT_TICK_END. Defer only its new position; retain look and flags.
        if (movement.hasRotation()) {
            return new ServerboundMovePlayerPacket.Rot(movement.getYRot(lastYaw), movement.getXRot(lastPitch),
                    movement.isOnGround(), movement.horizontalCollision());
        }
        return new ServerboundMovePlayerPacket.StatusOnly(movement.isOnGround(), movement.horizontalCollision());
    }

    public static synchronized void beginServerPositionCorrection(Connection connection) {
        useConnection(connection);
        handlingServerPositionCorrection = true;
    }

    public static synchronized void endServerPositionCorrection(Connection connection) {
        if (currentConnection == connection) handlingServerPositionCorrection = false;
    }

    public static synchronized boolean isHandlingServerPositionCorrection(Connection connection) {
        return currentConnection == connection && handlingServerPositionCorrection;
    }
}
