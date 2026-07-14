package com.agora.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }

    /**
     * Plain REST against OpenSearch: the queries below are the actual wire API
     * (match, _doc PUT) — more instructive here than a fat client SDK, and no
     * client/server version coupling.
     */
    @Bean
    RestClient openSearch(@Value("${search.opensearch.url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
