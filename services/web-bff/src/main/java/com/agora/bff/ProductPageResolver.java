package com.agora.bff;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * API composition at the edge: the client asks ONE GraphQL question; the BFF
 * fans out to three services. In the Tempo trace this shows as one root with
 * three child HTTP spans — the aggregation-vs-chattiness demo. (DataLoader
 * batching is the N+1 fix once list fields arrive.)
 */
@Controller
public class ProductPageResolver {

    private final RestClient catalog;
    private final RestClient inventory;
    private final RestClient orders;

    public ProductPageResolver(RestClient.Builder builder,
                               @Value("${bff.catalog-uri}") String catalogUri,
                               @Value("${bff.inventory-uri}") String inventoryUri,
                               @Value("${bff.order-uri}") String orderUri) {
        this.catalog = builder.baseUrl(catalogUri).build();
        this.inventory = builder.baseUrl(inventoryUri).build();
        this.orders = builder.baseUrl(orderUri).build();
    }

    public record StateCount(String state, int n) {
    }

    public record ProductPage(Map<?, ?> product, Integer stock, List<StateCount> orderStats) {
    }

    @QueryMapping
    public ProductPage productPage(@Argument String productId) {
        Map<?, ?> product = catalog.get().uri("/api/v1/products/{id}", productId).retrieve().body(Map.class);

        Integer stock = null;
        try {
            Map<?, ?> inv = inventory.get().uri("/api/v1/inventory/{id}", productId).retrieve().body(Map.class);
            stock = inv == null ? null : ((Number) inv.get("stock")).intValue();
        } catch (Exception ignored) {
            // partial degradation: page renders without stock (graceful BFF)
        }

        List<StateCount> stats = List.of();
        try {
            Map<?, ?> s = orders.get().uri("/api/v1/orders/stats?productId={id}", productId).retrieve().body(Map.class);
            stats = ((List<Map<String, Object>>) s.get("byState")).stream()
                    .map(m -> new StateCount((String) m.get("state"), ((Number) m.get("n")).intValue()))
                    .toList();
        } catch (Exception ignored) {
        }

        return new ProductPage(product, stock, stats);
    }
}
