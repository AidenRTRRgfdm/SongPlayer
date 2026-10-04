package com.github.hhhzzzsss.songplayer.test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatPacket;

import java.util.ArrayList;
import java.util.List;

/** Test-only observation of local command output and real outgoing chat packets. */
public final class ChatProbe {
    private static final List<String> OUTPUT = new ArrayList<>();
    private static final List<String> SENT = new ArrayList<>();
    public static synchronized void output(Component message) { OUTPUT.add(message.getString()); }
    public static synchronized void sent(Packet<?> packet) {
        if (packet instanceof ServerboundChatPacket chat) SENT.add(chat.message());
    }
    public static synchronized boolean hasOutput(String text) { return OUTPUT.stream().anyMatch(line -> line.contains(text)); }
    public static synchronized List<String> sent() { return List.copyOf(SENT); }
    public static synchronized void reset() { OUTPUT.clear(); SENT.clear(); }
}
