package com.agora.catalog;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Provider verification: replays the committed pact (contracts/pacts) against
 * the RUNNING catalog-service on :8083, with provider state seeded straight
 * into catalogdb. Skips (does not fail) when the service isn't up — it's an
 * IT, run via `mvn -pl services/catalog-service verify` with the stack live.
 */
@Provider("catalog-service")
@PactFolder("../../contracts/pacts")
@ExtendWith(PactVerificationInvocationContextProvider.class)
class CatalogPactProviderIT {

    static final String BASE = System.getProperty("catalog.url", "http://localhost:8083");
    static final String DB = System.getProperty("catalog.db",
            "jdbc:postgresql://localhost:5433/catalogdb?user=agora&password=agora_local");

    @BeforeAll
    static void requireRunningProvider() {
        Assumptions.assumeTrue(reachable(BASE + "/actuator/health"),
                "catalog-service not running on " + BASE + " — provider verification skipped");
    }

    @BeforeEach
    void target(PactVerificationContext context) {
        URI base = URI.create(BASE);
        context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
    }

    @TestTemplate
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("product 1234 exists")
    void product1234Exists() throws Exception {
        try (Connection c = DriverManager.getConnection(DB)) {
            c.createStatement().execute("""
                    INSERT INTO products (id, name, description, price_cents, seller_id)
                    VALUES (1234, 'Sample Product', 'pact state row', 4200, 'seller-1')
                    ON CONFLICT (id) DO NOTHING""");
        }
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(1500);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
