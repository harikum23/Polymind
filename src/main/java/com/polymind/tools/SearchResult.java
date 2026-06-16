package com.polymind.tools;

import java.util.List;

/** Web-search outcome: a synthesized text block, the source list, and whether it was cached. */
public record SearchResult(String text, List<Source> sources, boolean cacheHit) {

    public record Source(String title, String url, String snippet) {}
}
