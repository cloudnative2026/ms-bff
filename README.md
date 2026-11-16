# ms-bff

Backend for Frontend de las pantallas de Reportes y Auditoria. Java 21 / Spring Boot, sin base de datos.
Valida el token Entra (rol Admin) y lo reenvia a ms-report, ms-audit y ms-catalog.

## Ejecutar

```sh
./mvnw spring-boot:run
```

Configuracion: MS_REPORT (http://localhost:8084), MS_AUDIT (http://localhost:8083),
MS_CATALOG (http://localhost:8080), SERVER_PORT, ENTRA_ISSUER, ENTRA_AUDIENCE.

## API (rol Admin)

| Metodo | Ruta | Destino |
| --- | --- | --- |
| GET | /api/v1/bff/reports/dashboard?from=&to=&topLimit=5 | kpis + sales + lead-time + top-products de ms-report, con nombres de ms-catalog |
| GET | /api/v1/bff/audit/events?user=&type=&orderId=&from=&to=&page=&size= | ms-audit |
| GET | /api/v1/bff/audit/event-types | ms-audit |

Errores 4xx de los servicios se devuelven tal cual; servicio caido -> 502.
Si ms-catalog no responde, el dashboard sale igual sin nombres de producto.
