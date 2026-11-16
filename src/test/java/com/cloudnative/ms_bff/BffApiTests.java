package com.cloudnative.ms_bff;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BffApiTests {
    static HttpServer server;
    // path -> [status, body]; requests record the raw query and the Authorization header.
    static final Map<String, Object[]> responses = new ConcurrentHashMap<>();
    static final Map<String, String> queries = new ConcurrentHashMap<>();
    static final Map<String, String> tokens = new ConcurrentHashMap<>();
    @Autowired MockMvc mvc;

    @DynamicPropertySource static void urls(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            queries.put(path, exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
            tokens.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            var response = responses.getOrDefault(path, new Object[] {404, "{}"});
            var body = ((String) response[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", (int) response[0] >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders((int) response[0], body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var url = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("report.service.url", () -> url);
        registry.add("audit.service.url", () -> url);
        registry.add("catalog.service.url", () -> url);
    }

    @AfterAll static void stop() { server.stop(0); }

    @BeforeEach void setup() {
        responses.clear(); queries.clear(); tokens.clear();
        responses.put("/api/v1/report/kpis", new Object[] {200, "{\"orders\":2,\"revenue\":45.00}"});
        responses.put("/api/v1/report/sales", new Object[] {200, "[{\"date\":\"2026-11-02\",\"orders\":1,\"revenue\":30.00}]"});
        responses.put("/api/v1/report/lead-time", new Object[] {200, "[{\"date\":\"2026-11-02\",\"deliveredOrders\":0}]"});
        responses.put("/api/v1/report/top-products", new Object[] {200,
            "[{\"productId\":1,\"quantity\":4,\"revenue\":40.00},{\"productId\":2,\"quantity\":1,\"revenue\":5.00}]"});
        responses.put("/api/v1/catalog", new Object[] {200, "[{\"id\":1,\"name\":\"Cafe\",\"price\":10}]"});
        responses.put("/api/v1/audit/events", new Object[] {200, "{\"content\":[],\"totalElements\":0}"});
    }

    static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.tokenValue("user-token")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static String param(String path, String name) {
        for (var pair : queries.get(path).split("&")) {
            var parts = pair.split("=", 2);
            if (parts[0].equals(name)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    @Test void dashboardCombinesReportsWithProductNamesForOneRange() throws Exception {
        mvc.perform(get("/api/v1/bff/reports/dashboard").param("from", "2026-11-02T03:00:00Z")
                .param("to", "2026-11-04T03:00:00Z").with(role("Admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kpis.orders").value(2))
            .andExpect(jsonPath("$.sales[0].revenue").value(30.00))
            .andExpect(jsonPath("$.leadTime[0].deliveredOrders").value(0))
            .andExpect(jsonPath("$.topProducts[0].name").value("Cafe"))
            .andExpect(jsonPath("$.topProducts[1].name").value(nullValue()))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty());
        for (var path : new String[] {"/api/v1/report/kpis", "/api/v1/report/sales", "/api/v1/report/lead-time",
                "/api/v1/report/top-products"}) {
            assertThat(param(path, "from")).isEqualTo("2026-11-02T03:00:00Z");
            assertThat(param(path, "to")).isEqualTo("2026-11-04T03:00:00Z");
            assertThat(tokens.get(path)).isEqualTo("Bearer user-token");
        }
        assertThat(tokens.get("/api/v1/catalog")).isEqualTo("Bearer user-token");
    }

    @Test void catalogOutageOnlyDropsTheNames() throws Exception {
        responses.put("/api/v1/catalog", new Object[] {503, "{}"});
        mvc.perform(get("/api/v1/bff/reports/dashboard").with(role("Admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.topProducts[0].name").value(nullValue()));
    }

    @Test void downstreamErrorsAreMapped() throws Exception {
        responses.put("/api/v1/report/kpis", new Object[] {400, "{\"status\":400,\"detail\":\"Rango invalido\"}"});
        mvc.perform(get("/api/v1/bff/reports/dashboard").with(role("Admin")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Rango invalido"));
        responses.put("/api/v1/report/kpis", new Object[] {500, "{}"});
        mvc.perform(get("/api/v1/bff/reports/dashboard").with(role("Admin"))).andExpect(status().isBadGateway());
    }

    @Test void auditFiltersAreForwardedEncoded() throws Exception {
        mvc.perform(get("/api/v1/bff/audit/events").param("user", "ana+b {x}&y").param("type", "OrderCreated")
                .param("orderId", "7").param("size", "20").with(role("Admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        var path = "/api/v1/audit/events";
        assertThat(param(path, "user")).isEqualTo("ana+b {x}&y");
        assertThat(param(path, "type")).isEqualTo("OrderCreated");
        assertThat(param(path, "orderId")).isEqualTo("7");
        assertThat(param(path, "size")).isEqualTo("20");
        assertThat(param(path, "from")).isNull();
    }

    @Test void onlyAdminsAndCorsForTheFrontend() throws Exception {
        mvc.perform(get("/api/v1/bff/reports/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bff/audit/events").with(role("Operador"))).andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/bff/audit/events").header("Origin", "https://cloudnative2026.github.io")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://cloudnative2026.github.io"));
    }
}
