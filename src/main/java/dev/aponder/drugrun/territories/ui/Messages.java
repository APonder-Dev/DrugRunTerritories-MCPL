/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

/**
 * MiniMessage texts from {@code messages.yml}. Values supplied by players or other plugins
 * (faction names, player names) are inserted as unparsed text so they cannot inject formatting
 * or click events. Display names from territory definitions are inserted parsed, since only
 * administrators write them.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> texts = new HashMap<>();
    private String prefix = "";

    /** Loads every string under the root of {@code messages.yml}. */
    public void load(ConfigurationSection root) {
        texts.clear();
        for (String key : root.getKeys(true)) {
            if (root.isString(key)) {
                texts.put(key, root.getString(key));
            } else if (root.isList(key)) {
                texts.put(key, String.join("\n", root.getStringList(key)));
            }
        }
        prefix = texts.getOrDefault("prefix", "");
    }

    /** @return the rendered message, or a visible marker when the key is missing */
    public Component get(String key, TagResolver... resolvers) {
        String raw = texts.get(key);
        if (raw == null) {
            return Component.text("[missing message: " + key + "]");
        }
        return MM.deserialize(raw.replace("<prefix>", prefix), TagResolver.resolver(resolvers));
    }

    /** @return whether the key exists and is not empty (empty texts are used to switch messages off) */
    public boolean has(String key) {
        String raw = texts.get(key);
        return raw != null && !raw.isEmpty();
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        if (has(key)) {
            audience.sendMessage(get(key, resolvers));
        }
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        if (has(key)) {
            audience.sendActionBar(get(key, resolvers));
        }
    }

    /** Untrusted text (faction or player names). */
    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** Trusted MiniMessage (administrator-written display names). */
    public static TagResolver mini(String name, String miniMessage) {
        return Placeholder.parsed(name, miniMessage == null ? "" : miniMessage);
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /** @return plain text of a MiniMessage string, for console output and placeholders */
    public static String plain(String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(MM.deserialize(miniMessage == null ? "" : miniMessage));
    }

    /** @return keys present in {@code defaults} but missing here (for startup warnings) */
    public List<String> missingFrom(ConfigurationSection defaults) {
        return defaults.getKeys(true).stream().filter(defaults::isString).filter(k -> !texts.containsKey(k)).toList();
    }
}
