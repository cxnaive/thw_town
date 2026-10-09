package cn.lunadeer.dominion.utils.stui.components.buttons;

import cn.lunadeer.dominion.utils.stui.ViewStyles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.function.Function;

public class Button {

    private String prefix = "[";
    private String suffix = "]";
    protected String text = "";
    // Factory methods retain their binary signatures across Adventure 4 and 5.
    protected Function<String, ClickEvent> action = null;
    private String hoverText = "";
    protected String clickExecute = "";
    TextColor color = ViewStyles.ACTION;
    TextColor disabledColor = TextColor.color(0x666666);

    private String disabledText = null;

    public TextComponent build() {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text(prefix, color));
        builder.append(Component.text(text, color));
        builder.append(Component.text(suffix, color));
        if (action != null && !clickExecute.isEmpty() && disabledText == null) {
            builder.clickEvent(action.apply(clickExecute));
        }
        if (disabledText != null) {
            builder.hoverEvent(Component.text(disabledText)).style(Style.style(TextDecoration.STRIKETHROUGH));
        } else {
            if (!hoverText.isEmpty()) {
                builder.hoverEvent(Component.text(hoverText));
            }
        }
        return builder.build();
    }

    public Button(String text) {
        this.text = text;
    }

    public Button setText(String text) {
        this.text = text;
        return this;
    }

    public Button setPreSufIx(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
        return this;
    }

    public Button setHoverText(String hoverText) {
        this.hoverText = hoverText;
        return this;
    }

    public Button setColor(TextColor color) {
        this.color = color;
        return this;
    }

    public Button setDisabled(String hint) {
        this.disabledText = hint;
        this.color = disabledColor;
        return this;
    }

    public Button red() {
        this.color = ViewStyles.SEVERE;
        return this;
    }

    public Button green() {
        this.color = ViewStyles.NORMAL;
        return this;
    }
}
