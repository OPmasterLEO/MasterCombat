package net.opmasterleo.combat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatUtil {
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public static Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        Matcher matcher = HEX_COLOR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        input = sb.toString();
        input = input.replace('&', '§');
        
        return LegacyComponentSerializer.legacySection()
                .deserialize(input);
    }
    
    public static TextColor getLastColor(Component component) {
        if (component instanceof TextComponent tc && tc.color() != null) {
            return tc.color();
        }
        for (Component child : component.children()) {
            TextColor color = getLastColor(child);
            if (color != null) return color;
        }
        return null;
    }

    public static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static String toPlainText(String input) {
        if (input == null || input.isEmpty()) return "";
        input = input.replaceAll("&#[A-Fa-f0-9]{6}", "");
        input = input.replaceAll("§x§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]", "");
        input = input.replaceAll("&x&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]", "");
        input = input.replaceAll("[§&][0-9a-fk-or]", "");
        
        return input;
    }

    public static String toConsoleFormat(String input) {
        if (input == null || input.isEmpty()) return "";
        input = input.replace('&', '§');
        Matcher matcher = HEX_COLOR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        input = sb.toString();
        
        return input;
    }
}