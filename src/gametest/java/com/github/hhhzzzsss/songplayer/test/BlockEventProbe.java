package com.github.hhhzzzsss.songplayer.test;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/** Observes real integrated-server note events; never supplies fake packets or block states. */
public final class BlockEventProbe {
    private static final Set<BlockPos> PLAYED = new HashSet<>();

    public static void record(ClientboundBlockEventPacket packet) {
        if (packet.getBlock() == Blocks.NOTE_BLOCK && packet.getB0() == 0) PLAYED.add(packet.getPos());
    }

    public static void reset() { PLAYED.clear(); }
    public static boolean playedAll(Set<BlockPos> positions) { return PLAYED.containsAll(positions); }
    public static Set<BlockPos> played() { return Set.copyOf(PLAYED); }
}
