package com.github.hhhzzzsss.songplayer.playing;

import com.github.hhhzzzsss.songplayer.song.Instrument;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Converts block properties to SongPlayer's stable, sixteen-instrument note IDs. */
public final class NoteBlockNotes {
    private NoteBlockNotes() {}

    public static int getNoteId(BlockState state) {
        if (!state.is(Blocks.NOTE_BLOCK)) return -1;
        String name = state.getValue(NoteBlock.INSTRUMENT).getSerializedName();
        for (Instrument instrument : Instrument.values()) {
            if (instrument.name().equalsIgnoreCase(name)) {
                return instrument.instrumentId * 25 + state.getValue(NoteBlock.NOTE);
            }
        }
        // Trumpets and head instruments cannot be represented in the existing song format.
        return -1;
    }
}
