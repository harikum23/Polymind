package com.polymind.routing;

import java.util.Locale;
import java.util.Set;

/** Task categories used for classification and category-alias routing. */
public enum TaskCategory {
    CHAT, CODE, MATH, REASONING;

    private static final Set<String> ALIASES = Set.of("chat", "code", "math", "reasoning");

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static boolean isAlias(String value) {
        return value != null && ALIASES.contains(value.toLowerCase(Locale.ROOT));
    }

    public static TaskCategory from(String value) {
        return TaskCategory.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
