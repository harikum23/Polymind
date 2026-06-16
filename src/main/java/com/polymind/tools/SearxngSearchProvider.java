package com.polymind.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default web-search provider: self-hosted SearXNG (no API key, private). Queries the JSON API
 * ({@code /search?format=json}) over JDK HttpClient and synthesizes a text block + source list.
 */
@Component
public class SearxngSearchProvider implements WebSearchProvider {

    private final SearchProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SearxngSearchProvider(SearchProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public SearchResult search(String query, Integer days) {
        try {
            String url = props.getSearxng().getBaseUrl()
                    + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            if (days != null) {
                url += "&time_range=" + recencyRange(days);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new WebSearchException("SearXNG returned HTTP " + resp.statusCode());
            }
            return parse(resp.body());
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSearchException("SearXNG search failed: " + e.getMessage(), e);
        }
    }

    private SearchResult parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");
        List<SearchResult.Source> sources = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int n = 0;
        for (JsonNode r : results) {
            if (n >= props.getMaxResults()) {
                break;
            }
            String title = r.path("title").asText("");
            String urlStr = r.path("url").asText("");
            String snippet = r.path("content").asText("");
            sources.add(new SearchResult.Source(title, urlStr, snippet));
            text.append("[").append(n + 1).append("] ").append(title).append("\n")
                    .append(snippet).append("\n").append(urlStr).append("\n\n");
            n++;
        }
        return new SearchResult(text.toString().strip(), sources, false);
    }

    private String recencyRange(int days) {
        if (days <= 1) {
            return "day";
        }
        if (days <= 7) {
            return "week";
        }
        if (days <= 31) {
            return "month";
        }
        return "year";
    }
}
