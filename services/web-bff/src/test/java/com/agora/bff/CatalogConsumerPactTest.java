package com.agora.bff;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.annotations.PactDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Consumer side of the web-bff ↔ catalog contract. The generated pact file is
 * COMMITTED to contracts/pacts/ (broker-less: in a mono-repo the git history
 * is the broker); catalog-service verifies it in CatalogPactProviderIT.
 *
 * The contract pins only what this consumer actually reads (name, priceCents,
 * id, sellerId) — Postel's law: the producer may add fields freely.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "catalog-service", pactVersion = PactSpecVersion.V3)
@PactDirectory("../../contracts/pacts")
class CatalogConsumerPactTest {

    @Pact(consumer = "web-bff", provider = "catalog-service")
    RequestResponsePact productById(PactDslWithProvider builder) {
        return builder
                .given("product 1234 exists")
                .uponReceiving("a product page fetch by id")
                .path("/api/v1/products/1234")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .numberType("id", 1234)
                        .stringType("name", "Sample Product")
                        .numberType("priceCents", 4200)
                        .stringType("sellerId", "seller-1"))
                .toPact();
    }

    @Test
    void bffReadsTheFieldsItNeeds(MockServer mockServer) {
        // Point ONLY the catalog client at the pact mock; inventory/orders are
        // down on purpose — the resolver's partial degradation absorbs them.
        ProductPageResolver resolver = new ProductPageResolver(
                RestClient.builder(), mockServer.getUrl(), "http://localhost:1", "http://localhost:1");

        ProductPageResolver.ProductPage page = resolver.productPage("1234");

        assertNotNull(page.product());
        assertEquals("Sample Product", page.product().get("name"));
        assertEquals(4200, ((Number) page.product().get("priceCents")).intValue());
    }
}
