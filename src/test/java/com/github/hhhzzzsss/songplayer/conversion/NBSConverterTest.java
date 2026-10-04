package com.github.hhhzzzsss.songplayer.conversion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class NBSConverterTest {
    @Test
    void longSongsKeepOrderedTimestampsAndDoNotFinishImmediately() throws IOException {
        var song = NBSConverter.getSongFromBytes(
                fixture(0, 21474, 21475, 32767, 32768, 54953), "long-song.nbs");

        // Cross both the 32-bit multiplication overflow and signed-short tick boundary.
        long[] expectedTimes = {0, 447375, 447395, 682645, 682666, 1144854};
        assertEquals(expectedTimes.length, song.size());
        for (int i = 0; i < expectedTimes.length; i++) {
            assertEquals(expectedTimes[i], song.get(i).time, "note " + i);
            assertEquals(12, song.get(i).noteId);
            assertEquals(73, song.get(i).velocity);
        }
        assertEquals(1144904, song.length);
        assertFalse(song.finished());
    }

    @Test
    void sparseSongsReadTickJumpsAboveTheSignedShortRange() throws IOException {
        var song = NBSConverter.getSongFromBytes(fixture(39999), "sparse-song.nbs");

        // The first jump is 40000 because ticks begin at -1.
        assertEquals(833312, song.get(0).time);
        assertEquals(833362, song.length);
        assertFalse(song.finished());
    }

    @Test
    void cumulativeTicksCanContinuePastTheUnsignedShortRange() throws IOException {
        var song = NBSConverter.getSongFromBytes(fixture(60000, 100000), "extended-song.nbs");

        assertEquals(1250000, song.get(0).time);
        assertEquals(2083333, song.get(1).time);
        assertEquals(2083383, song.length);
    }

    private static byte[] fixture(int... ticks) {
        ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0); // New NBS format.
        buffer.put((byte) 5);
        buffer.put((byte) 16); // Vanilla instrument count.
        buffer.putShort((short) ticks[ticks.length - 1]);
        buffer.putShort((short) 1); // Layer count.
        putString(buffer, "Long song fixture");
        putString(buffer, ""); // Author.
        putString(buffer, ""); // Original author.
        putString(buffer, ""); // Description.
        buffer.putShort((short) 4800); // 48 ticks per second.
        buffer.put((byte) 0); // Auto-saving.
        buffer.put((byte) 10); // Auto-saving interval.
        buffer.put((byte) 4); // Time signature.
        for (int i = 0; i < 5; i++) buffer.putInt(0); // Editor statistics.
        putString(buffer, ""); // Imported file name.
        buffer.put((byte) 0); // Loop disabled.
        buffer.put((byte) 0); // Loop count.
        buffer.putShort((short) 0); // Loop start.

        int previousTick = -1;
        for (int tick : ticks) {
            buffer.putShort((short) (tick - previousTick));
            buffer.putShort((short) 1); // First layer.
            buffer.put((byte) 0); // Harp.
            buffer.put((byte) 45); // Note key.
            buffer.put((byte) 100); // Note velocity.
            buffer.put((byte) 100); // Center panning.
            buffer.putShort((short) 0); // Fine pitch.
            buffer.putShort((short) 0); // End layers at this tick.
            previousTick = tick;
        }
        buffer.putShort((short) 0); // End notes.
        putString(buffer, "Layer");
        buffer.put((byte) 0); // Layer unlocked.
        buffer.put((byte) 73); // Layer volume.
        buffer.put((byte) 100); // Layer stereo.
        buffer.put((byte) 0); // No custom instruments.
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }
}
