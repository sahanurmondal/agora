package com.agora.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final RestClient openSearch;
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    public SearchController(RestClient openSearch, StringRedisTemplate redis) {
        this.openSearch = openSearch;
        this.redis = redis;
    }

    /** Full-text over OpenSearch — eventually consistent with catalog writes (CDC lag). */
    @GetMapping
    public Map<String, Object> search(@RequestParam String q) throws Exception {
        String body = """
                {"query":{"multi_match":{"query":%s,"fields":["name^2","description"]}},"size":20}
                """.formatted(json.writeValueAsString(q));
        String resp = openSearch.post().uri("/products/_search")
                .header("Content-Type", "application/json")
                .body(body).retrieve().body(String.class);

        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode hit : json.readTree(resp).path("hits").path("hits")) {
            hits.add(hit.path("_source"));
        }
        return Map.of("count", hits.size(), "results", hits);
    }

    /** Top-k suggestions from Redis prefix sets — single-digit-ms path, no search engine. */
    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam String prefix) {
        Set<String> top = redis.opsForZSet()
                .reverseRange("ac:" + prefix.toLowerCase(Locale.ROOT).trim(), 0, 9);
        return Map.of("suggestions", top == null ? List.of() : top);
    }
}
