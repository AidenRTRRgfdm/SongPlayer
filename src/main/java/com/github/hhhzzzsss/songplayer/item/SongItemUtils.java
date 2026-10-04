package com.github.hhhzzzsss.songplayer.item;

import com.github.hhhzzzsss.songplayer.Util;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class SongItemUtils {
    public static final String SONG_ITEM_KEY = "SongItemData";
    public static final String SONG_DATA_KEY = "SongData";
    public static final String FILE_NAME_KEY = "FileName";
    public static final String DISPLAY_NAME_KEY = "DisplayName";

    public static ItemStack createSongItem(ItemStack stack, byte[] songData, String filename, String displayName) {
        CompoundTag songItemTag = new CompoundTag();
        songItemTag.putString(SONG_DATA_KEY, Base64.getEncoder().encodeToString(songData));
        songItemTag.putString(FILE_NAME_KEY, filename);
        songItemTag.putString(DISPLAY_NAME_KEY, displayName);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, nbt -> nbt.put(SONG_ITEM_KEY, songItemTag));
        addSongItemDisplay(stack);
        return stack;
    }

    public static void addSongItemDisplay(ItemStack stack) {
        getSongItemTag(stack).ifPresent((songItemTag) -> {
            String name = songItemTag.getString(DISPLAY_NAME_KEY)
                    .or(() -> songItemTag.getString(FILE_NAME_KEY))
                    .orElse("unnamed");
            Util.setItemName(stack,
                    Util.getStyledText(name, Style.EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(false))
            );
            Util.setItemLore(stack,
                    Util.getStyledText("Song Item", Style.EMPTY.withColor(ChatFormatting.YELLOW).withItalic(false)),
                    Util.getStyledText("Right click to play", Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(false)),
                    Util.getStyledText("Requires SongPlayer 3.0+", Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(false)),
                    Util.getStyledText("https://github.com/hhhzzzsss/SongPlayer", Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false))
            );
        });
    }

    public static void updateSongItemTag(ItemStack stack, Consumer<CompoundTag> nbtSetter) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, (itemNbt) -> {
            itemNbt.getCompound(SONG_ITEM_KEY).ifPresent(nbtSetter);
        });
    }

    public static Optional<CompoundTag> getSongItemTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getCompound(SONG_ITEM_KEY);
    }

    public static boolean isSongItem(ItemStack stack) {
        return getSongItemTag(stack).isPresent();
    }

    public static byte[] getSongData(ItemStack stack) throws IllegalArgumentException {
        return getSongItemTag(stack)
                .flatMap((songItemTag) -> songItemTag.getString(SONG_DATA_KEY))
                .map((songData) -> Base64.getDecoder().decode(songData))
                .orElse(null);
    }
}
