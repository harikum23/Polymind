package com.polymind.tools;

/**
 * Provider-agnostic web-search port (ARCHITECTURE.md §8.2). Default impl is SearXNG; an API provider
 * (Brave/Tavily/Gemini) is a drop-in alternative selected via {@code polymind.search.provider}.
 */
public interface WebSearchProvider {

    String name();

    /** Execute a search; {@code days} optionally restricts recency (null = no restriction). */
    SearchResult search(String query, Integer days);
}
