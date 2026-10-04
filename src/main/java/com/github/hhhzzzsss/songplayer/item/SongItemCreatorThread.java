package com.github.hhhzzzsss.songplayer.item;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.conversion.SPConverter;
import com.github.hhhzzzsss.songplayer.song.SongLoaderThread;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

public class SongItemCreatorThread extends SongLoaderThread {
    public final int slotId;
    public final ItemStack stack;
    public SongItemCreatorThread(String location) throws IOException {
        super(location);
        this.slotId = SongPlayer.MC.player.getInventory().getSelectedSlot();
        this.stack = SongPlayer.MC.player.getInventory().getItem(slotId);
    }

    @Override
    public void run() {
        super.run();
        byte[] songData;
        try {
            songData = SPConverter.getBytesFromSong(song);
        } catch (IOException e) {
            Util.showChatMessage("§cError creating song item: §4" + e.getMessage());
            return;
        }
        SongPlayer.MC.execute(() -> {
            if (SongPlayer.MC.level == null) {
                return;
            }
            if (!SongPlayer.MC.player.getInventory().getItem(slotId).equals(stack)) {
                Util.showChatMessage("§cCould not create song item because item has moved");
            }
            ItemStack newStack;
            if (stack.isEmpty()) {
                newStack = Items.PAPER.getDefaultInstance();
                // When going from 1.21.3 -> 1.21.4, datafixer changes the custom model data to a float array with one element
                newStack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(751642938f), List.of(), List.of(), List.of()));
            }
            else {
                newStack = stack.copy();
            }
            newStack = SongItemUtils.createSongItem(newStack, songData, filename, song.name);
            SongPlayer.MC.player.getInventory().setItem(slotId, newStack);
            SongPlayer.MC.gameMode.handleCreativeModeItemAdd(SongPlayer.MC.player.getItemInHand(InteractionHand.MAIN_HAND), 36 + slotId);
            Util.showChatMessage(Component.literal("§6Successfully assigned song data to §3").append(newStack.getItem().getName(newStack)));
        });
    }
}
