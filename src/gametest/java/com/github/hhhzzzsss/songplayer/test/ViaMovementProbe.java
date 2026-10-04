package com.github.hhhzzzsss.songplayer.test;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;

/** Records outgoing vanilla teleport acknowledgements in the disposable protocol fixture. */
public final class ViaMovementProbe {
    private static int acknowledgements;
    private static int positions;
    private static int rotations;
    private static int tickEnds;

    public static void sent(Packet<?> packet) {
        if (packet instanceof ServerboundAcceptTeleportationPacket) acknowledgements++;
        else if (packet instanceof ServerboundClientTickEndPacket) tickEnds++;
        else if (packet instanceof ServerboundMovePlayerPacket movement) {
            if (movement.hasPosition()) positions++;
            else if (movement.hasRotation()) rotations++;
        }
    }

    public static void reset() { acknowledgements = positions = rotations = tickEnds = 0; }
    public static int acknowledgements() { return acknowledgements; }
    public static String diagnostics() {
        return "teleportAcks=" + acknowledgements + " positions=" + positions + " rotations=" + rotations + " tickEnds=" + tickEnds;
    }
}
