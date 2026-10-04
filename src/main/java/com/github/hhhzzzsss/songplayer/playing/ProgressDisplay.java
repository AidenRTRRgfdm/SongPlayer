package com.github.hhhzzzsss.songplayer.playing;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class ProgressDisplay {
    private static ProgressDisplay instance = null;
    public static ProgressDisplay getInstance() {
        if (instance == null) {
            instance = new ProgressDisplay();
        }
        return instance;
    }
    private ProgressDisplay() {}

    public MutableComponent topText = Component.empty();
    public MutableComponent bottomText = Component.empty();
    public int fade = 0;

    public void setText(MutableComponent bottomText, MutableComponent topText) {
        this.bottomText = bottomText;
        this.topText = topText;
        fade = 100;
    }

    public void onRenderHUD(GuiGraphicsExtractor context, int heldItemTooltipFade) {
        if (fade <= 0) {
            return;
        }

        int bottomTextWidth = SongPlayer.MC.font.width(bottomText);
        int topTextWidth = SongPlayer.MC.font.width(topText);
        int bottomTextX = (SongPlayer.MC.getWindow().getGuiScaledWidth() - bottomTextWidth) / 2;
        int topTextX = (SongPlayer.MC.getWindow().getGuiScaledWidth() - topTextWidth) / 2;
        int bottomTextY = SongPlayer.MC.getWindow().getGuiScaledHeight() - 59;
        if (!SongPlayer.MC.gameMode.canHurtPlayer()) {
            bottomTextY += 14;
        }
        if (heldItemTooltipFade > 0) {
            bottomTextY -= 12;
        }
        int topTextY = bottomTextY - 12;

        int opacity = (int)((float)this.fade * 256.0F / 10.0F);
        if (opacity > 255) {
            opacity = 255;
        }

        Objects.requireNonNull(SongPlayer.MC.font);
        context.text(SongPlayer.MC.font, bottomText, bottomTextX, bottomTextY, 16777215 + (opacity << 24));
        context.text(SongPlayer.MC.font, topText, topTextX, topTextY, 16777215 + (opacity << 24));
    }

    public void onTick() {
        if (fade > 0) {
            fade--;
        }
    }
}
