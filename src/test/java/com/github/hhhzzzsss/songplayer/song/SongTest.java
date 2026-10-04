package com.github.hhhzzzsss.songplayer.song;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Playback contracts without Minecraft, wall-clock sleeps, or a running server. */
final class SongTest {
    @Test
    void sortsChordsByPitchAndTracksEveryRequiredInstrument() {
        Song song = new Song("chord");
        song.add(new Note(399, 200));
        song.add(new Note(25, 0));
        song.add(new Note(0, 0));
        song.sort();

        assertEquals(0, song.get(0).noteId);
        assertEquals(25, song.get(1).noteId);
        assertEquals(399, song.get(2).noteId);
        assertTrue(song.requiredNotes[0]);
        assertTrue(song.requiredNotes[25]);
        assertTrue(song.requiredNotes[399]);
        assertFalse(song.requiredNotes[398]);
    }

    @Test
    void seekingRetainsNotesExactlyOnTheBoundary() {
        Song song = fixture();
        song.setTime(100);
        assertTrue(song.reachedNextNote());
        assertEquals(1, song.getNextNote().noteId);
        assertFalse(song.reachedNextNote());
        song.setTime(101);
        assertEquals(2, song.position);
        song.setTime(0);
        assertEquals(0, song.position);
    }

    @Test
    void finiteLoopSkipsIntroAndPreservesElapsedRemainder() {
        Song song = fixture();
        song.looping = true;
        song.loopPosition = 100;
        song.loopCount = 1;
        song.position = song.size();
        song.time = 351;

        assertTrue(song.reachedNextNote());
        assertEquals(151, song.time);
        assertEquals(1, song.currentLoop);
        assertEquals(1, song.getNextNote().noteId);
        assertFalse(song.reachedNextNote());
        song.time = 200;
        assertEquals(2, song.getNextNote().noteId);
        song.time = 301;
        assertTrue(song.finished());
        assertNull(song.getNextNote());
    }

    @Test
    void infiniteLoopAndResetKeepPlaybackReusable() {
        Song song = fixture();
        song.looping = true;
        song.loopCount = 0;
        song.position = song.size();
        song.time = 301;
        assertFalse(song.finished());
        assertTrue(song.reachedNextNote());
        assertEquals(0, song.getNextNote().noteId);
        song.reset();
        assertTrue(song.paused);
        assertEquals(0, song.position);
        assertEquals(0, song.time);
        assertEquals(0, song.currentLoop);
        assertTrue(song.looping);
    }

    @Test
    void repeatedPlayDoesNotRestartAnAlreadyPlayingSong() {
        Song song = fixture();
        song.setTime(100);
        song.play();
        long started = song.startTime;
        song.play();
        assertEquals(started, song.startTime);
        song.pause();
        assertTrue(song.paused);
        long pausedAt = song.time;
        song.pause();
        assertEquals(pausedAt, song.time);
    }

    private static Song fixture() {
        Song song = new Song("fixture");
        song.add(new Note(0, 0));
        song.add(new Note(1, 100));
        song.add(new Note(2, 200));
        song.length = 300;
        return song;
    }
}
