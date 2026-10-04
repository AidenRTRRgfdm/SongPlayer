package com.github.hhhzzzsss.songplayer.mixin;

import com.github.hhhzzzsss.songplayer.CommandProcessor;
import com.github.hhhzzzsss.songplayer.Config;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;

@Mixin(CommandSuggestions.class)
public class ChatInputSuggestorMixin {
    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    private static int getLastWordIndex(String input) {
        return 0;
    }

    @Shadow
    public void showSuggestions(boolean narrateFirstSuggestion) {}

    @Shadow
    @Final
    private final EditBox input;

    public ChatInputSuggestorMixin() {
        input = null;
    }

    @Inject(at = @At("TAIL"), method = "updateCommandInfo()V")
    public void onRefresh(CallbackInfo ci) {
        String textStr = this.input.getValue();
        int cursorPos = this.input.getCursorPosition();
        String preStr = textStr.substring(0, cursorPos);
        if (!preStr.startsWith(Config.getConfig().prefix)) {
            return;
        }

        int wordStart = getLastWordIndex(preStr);
        CompletableFuture<Suggestions> suggestions;
        try {
            suggestions = CommandProcessor.handleSuggestions(preStr, new SuggestionsBuilder(preStr, wordStart));
        }
        catch (Throwable e) {
            suggestions = null;
        }
        if (suggestions != null) {
            this.pendingSuggestions = suggestions;
            this.showSuggestions(true);
        }
    }
}
