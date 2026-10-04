package com.github.hhhzzzsss.songplayer;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;

public class Util {
    static final Minecraft MC = Minecraft.getInstance();

    // IO

    public static void createDirectoriesSilently(Path path) {
        try {
            Files.createDirectories(path);
        }
        catch (IOException e) {}
    }

    public static Path resolveWithIOException(Path path, String other) throws IOException {
        try {
            return path.resolve(other);
        }
        catch (InvalidPathException e) {
            throw new IOException(e.getMessage());
        }
    }

    public static class LimitedSizeInputStream extends InputStream {
        private final InputStream original;
        private final long maxSize;
        private long total;

        public LimitedSizeInputStream(InputStream original, long maxSize) {
            this.original = original;
            this.maxSize = maxSize;
        }

        @Override
        public int read() throws IOException {
            int i = original.read();
            if (i>=0) incrementCounter(1);
            return i;
        }

        @Override
        public int read(byte b[]) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            int i = original.read(b, off, len);
            if (i>=0) incrementCounter(i);
            return i;
        }

        private void incrementCounter(int size) throws IOException {
            total += size;
            if (total>maxSize) throw new IOException("Input stream exceeded maximum size of " + maxSize + " bytes");
        }
    }

    // Time

    public static String formatTime(long milliseconds) {
        long temp = Math.abs(milliseconds);
        temp /= 1000;
        long seconds = temp % 60;
        temp /= 60;
        long minutes = temp % 60;
        temp /= 60;
        long hours = temp;
        StringBuilder sb = new StringBuilder();
        if (milliseconds < 0) {
            sb.append("-");
        }
        if (hours > 0) {
            sb.append(String.format("%d:", hours));
            sb.append(String.format("%02d:", minutes));
        } else {
            sb.append(String.format("%d:", minutes));
        }
        sb.append(String.format("%02d", seconds));
        return sb.toString();
    }

    public static Pattern timePattern = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d+)");
    public static long parseTime(String timeStr) throws IOException {
        Matcher matcher = timePattern.matcher(timeStr);
        if (matcher.matches()) {
            long time = 0;
            String hourString = matcher.group(1);
            String minuteString = matcher.group(2);
            String secondString = matcher.group(3);
            if (hourString != null) {
                time += Integer.parseInt(hourString) * 60 * 60 * 1000;
            }
            time += Integer.parseInt(minuteString) * 60 * 1000;
            time += Double.parseDouble(secondString) * 1000.0;
            return time;
        } else {
            throw new IOException("Invalid time pattern");
        }
    }

    // Command Suggestions

    public static CompletableFuture<Suggestions> giveSongSuggestions(String arg, SuggestionsBuilder suggestionsBuilder) {
        int lastSlash = arg.lastIndexOf("/");
        String dirString = "";
        Path dir = SongPlayer.SONG_DIR;
        if (lastSlash >= 0) {
            dirString = arg.substring(0, lastSlash+1);
            try {
                dir = resolveWithIOException(dir, dirString);
            }
            catch (IOException e) {
                return null;
            }
        }

        Stream<Path> songFiles;
        try {
            songFiles = Files.list(dir);
        } catch (IOException e) {
            return null;
        }

        int clipStart;
        if (arg.contains(" ")) {
            clipStart = arg.lastIndexOf(" ") + 1;
        }
        else {
            clipStart = 0;
        }

        ArrayList<String> suggestionsList = new ArrayList<>();
        for (Path path : songFiles.collect(Collectors.toList())) {
            if (Files.isRegularFile(path)) {
                suggestionsList.add(dirString + path.getFileName().toString());
            }
            else if (Files.isDirectory(path)) {
                suggestionsList.add(dirString + path.getFileName().toString() + "/");
            }
        }
        Stream<String> suggestions = suggestionsList.stream()
                .filter(str -> str.startsWith(arg))
                .map(str -> str.substring(clipStart));
        return SharedSuggestionProvider.suggest(suggestions, suggestionsBuilder);
    }

    public static CompletableFuture<Suggestions> givePlaylistSuggestions(SuggestionsBuilder suggestionsBuilder) {
        if (!Files.exists(SongPlayer.PLAYLISTS_DIR)) return null;
        try {
            return SharedSuggestionProvider.suggest(
                    Files.list(SongPlayer.PLAYLISTS_DIR)
                            .filter(Files::isDirectory)
                            .map(Path::getFileName)
                            .map(Path::toString),
                    suggestionsBuilder);
        } catch (IOException e) {
            return null;
        }
    }

    public static CompletableFuture<Suggestions> giveSongDirectorySuggestions(String arg, SuggestionsBuilder suggestionsBuilder) {
        int lastSlash = arg.lastIndexOf("/");
        String dirString;
        Path dir = SongPlayer.SONG_DIR;
        if (lastSlash >= 0) {
            dirString = arg.substring(0, lastSlash+1);
            try {
                dir = resolveWithIOException(dir, dirString);
            }
            catch (IOException e) {
                return null;
            }
        }
        else {
            dirString = "";
        }

        Stream<Path> songFiles;
        try {
            songFiles = Files.list(dir);
        } catch (IOException e) {
            return null;
        }

        int clipStart;
        if (arg.contains(" ")) {
            clipStart = arg.lastIndexOf(" ") + 1;
        }
        else {
            clipStart = 0;
        }

        Stream<String> suggestions = songFiles
                .filter(Files::isDirectory)
                .map(path -> dirString + path.getFileName().toString() + "/")
                .filter(str -> str.startsWith(arg))
                .map(str -> str.substring(clipStart));
        return SharedSuggestionProvider.suggest(suggestions, suggestionsBuilder);
    }

    // Text

    public static MutableComponent getStyledText(String str, Style style) {
        MutableComponent text = MutableComponent.create(PlainTextContents.create(str));
        text.setStyle(style);
        return text;
    }

    public static void setItemName(ItemStack stack, Component text) {
        stack.set(DataComponents.CUSTOM_NAME, text);
    }

    public static void setItemLore(ItemStack stack, Component... loreLines) {
        stack.set(DataComponents.LORE, new ItemLore(List.of(loreLines)));
    }

    public static MutableComponent joinTexts(MutableComponent base, Component... children) {
        if (base == null) {
            base = Component.empty();
        }
        for (Component child : children) {
            base.append(child);
        }
        return base;
    }

    // Server and World

    public static String getWorldName() {
        return MC.level.dimension().identifier().toString();
    }

    public static String getServerIdentifier() {
        if (MC.isLocalServer()) return "local;" + MC.getSingleplayerServer().getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
        else return "remote;" + MC.getCurrentServer().ip;
    }

    // Chat
    public static void showChatMessage(String message) {
        MC.gui.hud.getChat().addClientSystemMessage(Component.nullToEmpty(message));
    }

    public static void showChatMessage(Component text) {
        MC.gui.hud.getChat().addClientSystemMessage(text);
    }

    public static void sendChatMessage(String message) {
        MC.player.connection.sendChat(message);
    }

    public static void sendCommand(String command) {
        MC.player.connection.sendCommand(command);
    }
}
