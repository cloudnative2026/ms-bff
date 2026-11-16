package com.cloudnative.ms_bff.controller;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.cloudnative.ms_bff.client.Downstream;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

// Screen-oriented API for the Reports and Audit pages of the frontend.
@RestController
@RequestMapping("/api/v1/bff")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('Admin')")
public class BffController {
    private final Downstream downstream;

    public BffController(Downstream downstream) {
        this.downstream = downstream;
    }

    record TopProductRow(int productId, long quantity, BigDecimal revenue) {}
    public record TopProduct(int productId, String name, long quantity, BigDecimal revenue) {}
    public record Dashboard(Instant from, Instant to, JsonNode kpis, JsonNode sales, JsonNode leadTime,
            List<TopProduct> topProducts, Instant generatedAt) {}

    @GetMapping("/reports/dashboard")
    public Dashboard dashboard(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "5") int topLimit, JwtAuthenticationToken auth) {
        // One range for every widget, so they all describe the same period.
        var end = to != null ? to : Instant.now();
        var start = from != null ? from : end.minus(Duration.ofDays(30));
        var token = auth.getToken().getTokenValue();
        var range = params("from", start, "to", end);
        var kpis = downstream.report("/api/v1/report/kpis", range, token, JsonNode.class);
        var sales = downstream.report("/api/v1/report/sales", range, token, JsonNode.class);
        var leadTime = downstream.report("/api/v1/report/lead-time", range, token, JsonNode.class);
        var rows = downstream.report("/api/v1/report/top-products", params("from", start, "to", end, "limit", topLimit),
                token, TopProductRow[].class);
        Map<Long, String> names = downstream.products(token).stream().filter(p -> p.id() != null && p.name() != null)
                .collect(Collectors.toMap(Downstream.Product::id, Downstream.Product::name, (a, b) -> a));
        var top = List.of(rows).stream().map(r -> new TopProduct(r.productId(), names.get((long) r.productId()),
                r.quantity(), r.revenue())).toList();
        return new Dashboard(start, end, kpis, sales, leadTime, top, Instant.now());
    }

    @GetMapping("/audit/events")
    public JsonNode auditEvents(@RequestParam(required = false) String user,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, JwtAuthenticationToken auth) {
        return downstream.audit("/api/v1/audit/events", params("user", user, "type", type, "orderId", orderId,
                "from", from, "to", to, "page", page, "size", size), auth.getToken().getTokenValue(), JsonNode.class);
    }

    @GetMapping("/audit/event-types")
    public JsonNode auditEventTypes(JwtAuthenticationToken auth) {
        return downstream.audit("/api/v1/audit/event-types", Map.of(), auth.getToken().getTokenValue(), JsonNode.class);
    }

    // Like Map.of, but null values are allowed (and later skipped).
    private static Map<String, Object> params(Object... pairs) {
        var params = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) params.put((String) pairs[i], pairs[i + 1]);
        return params;
    }
}
