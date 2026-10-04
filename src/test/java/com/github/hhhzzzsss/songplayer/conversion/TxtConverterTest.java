package com.github.hhhzzzsss.songplayer.conversion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TxtConverterTest {
    @Test
    void importsCommentsCrLfAndTheHighestSupportedInstrument() throws IOException {
        var song = TxtConverter.getSongFromBytes(
                "# exported song\r\n0:12:0\r\n1:24:15\r\n2:0:4\r\n".getBytes(StandardCharsets.UTF_8),
                "fixture.txt");
        assertEquals("fixture.txt", song.name);
        assertEquals(3, song.size());
        assertEquals(12, song.get(0).noteId);
        assertEquals(399, song.get(1).noteId);
        assertEquals(50, song.get(1).time);
        assertEquals(100, song.get(2).noteId);
        assertEquals(150, song.length);
        assertTrue(song.requiredNotes[399]);
    }

    @Test
    void malformedInputIdentifiesTheActualSourceLine() {
        IOException error = assertThrows(IOException.class, () -> TxtConverter.getSongFromBytes(
                "# comment\n0:12:0\nbad:12:0".getBytes(StandardCharsets.UTF_8), "bad.txt"));
        assertTrue(error.getMessage().contains("line 3"));
    }
}
