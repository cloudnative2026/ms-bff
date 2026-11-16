package com.cloudnative.ms_bff.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

// HTTP clients for the services behind the BFF. Each call forwards the caller's bearer token.
@Component
public class Downstream {
    private static final Logger log = LoggerFactory.getLogger(Downstream.class);
    private final RestClient report;
    private final RestClient audit;
    private final RestClient catalog;

    public record Product(Long id, String name) {}

    public Downstream(@Value("${report.service.url}") String reportUrl, @Value("${audit.service.url}") String auditUrl,
            @Value("${catalog.service.url}") String catalogUrl) {
        report = client(reportUrl);
        audit = client(auditUrl);
        catalog = client(catalogUrl);
    }

    private static RestClient client(String url) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public <T> T report(String path, Map<String, ?> params, String token, Class<T> type) {
        return get(report, "ms-report", path, params, token, type);
    }

    public <T> T audit(String path, Map<String, ?> params, String token, Class<T> type) {
        return get(audit, "ms-audit", path, params, token, type);
    }

    // Product names only decorate the report, so a catalog outage degrades instead of failing.
    public List<Product> products(String token) {
        try {
            var products = catalog.get().uri("/api/v1/catalog").headers(h -> h.setBearerAuth(token))
                    .retrieve().body(Product[].class);
            return products == null ? List.of() : List.of(products);
        } catch (RestClientException exception) {
            log.warn("No se pudo consultar el catalogo", exception);
            return List.of();
        }
    }

    private static <T> T get(RestClient client, String service, String path, Map<String, ?> params, String token,
            Class<T> type) {
        try {
            return client.get().uri(uri(path, params)).headers(h -> h.setBearerAuth(token)).retrieve().body(type);
        } catch (HttpClientErrorException exception) {
            // Validation (400) and authorization (401/403) errors belong to the caller.
            ProblemDetail problem = null;
            try { problem = exception.getResponseBodyAs(ProblemDetail.class); } catch (RuntimeException ignored) {}
            throw new ResponseStatusException(exception.getStatusCode(), problem == null ? null : problem.getDetail());
        } catch (RestClientException exception) {
            log.warn("{} no disponible", service, exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, service + " no disponible.");
        }
    }

    // Values are passed as URI variables so they are fully encoded ('+', '&', '{', ...).
    private static Function<UriBuilder, URI> uri(String path, Map<String, ?> params) {
        return builder -> {
            builder.path(path);
            var values = new HashMap<String, Object>();
            params.forEach((name, value) -> {
                if (value != null) {
                    builder.queryParam(name, "{" + name + "}");
                    values.put(name, value);
                }
            });
            return builder.build(values);
        };
    }
}
