package com.github.hhhzzzsss.songplayer.item;

import com.github.hhhzzzsss.songplayer.Util;
import com.github.hhhzzzsss.songplayer.playing.SongHandler;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class SongItemConfirmationScreen extends Screen {
    private final SongItemLoaderThread loaderThread;
    private MultiLineLabel unloadedText;
    private MultiLineLabel loadedText;
    private boolean loaded = false;

    private static final Component CONFIRM = Component.literal("Play");
    private static final Component CANCEL = Component.literal("Cancel");

    public SongItemConfirmationScreen(ItemStack stack) throws IOException, IllegalArgumentException {
        super(Component.literal("Use song item"));
        this.loaderThread = new SongItemLoaderThread(stack);
        this.loaderThread.start();
    }

    @Override
    protected void init() {
        super.init();
        String unloadedMessage = "§7Loading song...";
        this.unloadedText = MultiLineLabel.create(this.font, Component.literal(unloadedMessage));
        if (loaded) addButtons(60 + loadedText.getLineCount() * this.font.lineHeight + 12);
    }

    private void addButtons(int y) {
        int centerX = this.width / 2;

        this.addRenderableWidget(Button.builder(CONFIRM, button -> {
            SongHandler.getInstance().loadSong(loaderThread);
            this.minecraft.gui.setScreen(null);
        }).bounds(centerX - 105, y, 100, 20).build());

        this.addRenderableWidget(Button.builder(CANCEL, button -> {
            this.minecraft.gui.setScreen(null);
        }).bounds(centerX + 5, y, 100, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (!loaderThread.isAlive()) {
            if (loaderThread.exception != null) {
                Util.showChatMessage("§cError loading song item: §4" + loaderThread.exception.getMessage());
                this.minecraft.gui.setScreen(null);
                return;
            }
            else if (loadedText == null) {
                String[] loadedMessages = {
                        "§3" + loaderThread.song.name,
                        String.format("§7Max notes per second: %s%d", getNumberColor(loaderThread.maxNotesPerSecond), loaderThread.maxNotesPerSecond),
                        String.format("§7Avg notes per second: %s%.2f", getNumberColor(loaderThread.avgNotesPerSecond), loaderThread.avgNotesPerSecond),
                };
                Component[] messageList = Arrays.stream(loadedMessages).map(Component::literal).toArray(Component[]::new);
                this.loadedText = MultiLineLabel.create(this.font, messageList);

                int loadedTextHeight = this.loadedText.getLineCount() * this.font.lineHeight;
                addButtons(60 + loadedTextHeight + 12);

                loaded = true;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, this.title, this.width / 2, 40, 0xFFFFFFFF);

        if (loaded) {
            loadedText.visitLines(TextAlignment.CENTER, this.width / 2, 60, 9, context.textRenderer());
        }
        else {
            unloadedText.visitLines(TextAlignment.CENTER, this.width / 2, 60, 9, context.textRenderer());
        }
    }

    public String getNumberColor(double number) {
        if (number < 50) {
            return "§a";
        }
        else if (number < 100) {
            return "§e";
        }
        else if (number < 300) {
            return "§6";
        }
        else if (number < 600) {
            return "§c";
        }
        else {
            return "§4";
        }

    }
}
