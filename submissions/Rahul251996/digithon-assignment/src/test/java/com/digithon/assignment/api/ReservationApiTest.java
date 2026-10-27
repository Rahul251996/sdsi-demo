package com.digithon.assignment.api;

import com.digithon.assignment.BaseIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-level tests using RestTemplate.
 * Tests HTTP status codes, response bodies, and validation error messages.
 */
class ReservationApiTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private RestTemplate restTemplate;
    private HttpHeaders jsonHeaders;

    @BeforeEach
    void setUp() {
        seedStandardProducts();
        restTemplate = new RestTemplate();
        jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    // =========================================================================
    // POST /api/reservations — success
    // =========================================================================

    @Test
    void shouldCreateReservationAndReturn201() throws Exception {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 2}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("RESERVED", json.get("status").asText());
        assertEquals("u1", json.get("userId").asText());
        assertEquals("A", json.get("sku").asText());
        assertEquals(2, json.get("quantity").asInt());
        assertTrue(json.has("reservationId"));
        assertTrue(json.has("expiresAt"));
    }

    @Test
    void shouldCreateReservationWithCustomTtl() throws Exception {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 1, "ttlSeconds": 60}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(1, json.get("quantity").asInt());
        assertEquals("RESERVED", json.get("status").asText());
    }

    // =========================================================================
    // POST /api/reservations — validation failures
    // =========================================================================

    @Test
    void shouldRejectMissingUserIdWith400() {
        String body = """
                {"sku": "A", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("userId"));
    }

    @Test
    void shouldRejectBlankUserIdWith400() {
        String body = """
                {"userId": "", "sku": "A", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectMissingSkuWith400() {
        String body = """
                {"userId": "u1", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectUnknownSkuWith404() throws Exception {
        String body = """
                {"userId": "u1", "sku": "UNKNOWN", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("NOT_FOUND", json.get("error").asText());
    }

    @Test
    void shouldRejectMissingQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A"}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectZeroQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 0}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectNegativeQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": -1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectFractionalQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 1.5}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectStringQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": "many"}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectExcessiveQuantityWith400() {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 99999}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectMalformedJsonWith400() {
        String body = "this is not json";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectEmptyBodyWith400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>("", headers),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void shouldRejectNonJsonContentType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>("hello", headers),
                String.class);

        // Spring Boot may return 415 or 400
        assertTrue(response.getStatusCode().is4xxClientError());
    }

    // =========================================================================
    // POST /api/reservations — business logic failures
    // =========================================================================

    @Test
    void shouldRejectInsufficientStockWith409() throws Exception {
        String body = """
                {"userId": "u1", "sku": "B", "quantity": 2}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("INSUFFICIENT_STOCK", json.get("error").asText());
    }

    @Test
    void shouldRejectZeroStockProductWith409() throws Exception {
        String body = """
                {"userId": "u1", "sku": "C", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("INSUFFICIENT_STOCK",
                objectMapper.readTree(response.getBody()).get("error").asText());
    }

    @Test
    void shouldRejectPurchaseLimitExceededWith409() throws Exception {
        // Reserve at limit
        String body1 = """
                {"userId": "u1", "sku": "A", "quantity": 2}""";
        restTemplate.postForEntity(baseUrl() + "/api/reservations",
                new HttpEntity<>(body1, jsonHeaders), String.class);

        // Try to exceed
        String body2 = """
                {"userId": "u1", "sku": "A", "quantity": 1}""";
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body2, jsonHeaders),
                String.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("PURCHASE_LIMIT_EXCEEDED",
                objectMapper.readTree(response.getBody()).get("error").asText());
    }

    // =========================================================================
    // POST /api/reservations/{id}/confirm
    // =========================================================================

    @Test
    void shouldConfirmReservationAndReturn200() throws Exception {
        // Create reservation
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 2}""";
        ResponseEntity<String> createResp = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders), String.class);
        String reservationId = objectMapper.readTree(createResp.getBody())
                .get("reservationId").asText();

        // Confirm
        ResponseEntity<String> confirmResp = restTemplate.postForEntity(
                baseUrl() + "/api/reservations/" + reservationId + "/confirm",
                new HttpEntity<>(jsonHeaders), String.class);

        assertEquals(HttpStatus.OK, confirmResp.getStatusCode());
        assertEquals("CONFIRMED",
                objectMapper.readTree(confirmResp.getBody()).get("status").asText());
    }

    // =========================================================================
    // POST /api/reservations/{id}/cancel
    // =========================================================================

    @Test
    void shouldCancelReservationAndReturn200() throws Exception {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 2}""";
        ResponseEntity<String> createResp = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders), String.class);
        String reservationId = objectMapper.readTree(createResp.getBody())
                .get("reservationId").asText();

        ResponseEntity<String> cancelResp = restTemplate.postForEntity(
                baseUrl() + "/api/reservations/" + reservationId + "/cancel",
                new HttpEntity<>(jsonHeaders), String.class);

        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());
        assertEquals("CANCELLED",
                objectMapper.readTree(cancelResp.getBody()).get("status").asText());
    }

    // =========================================================================
    // GET /api/reservations/{id}
    // =========================================================================

    @Test
    void shouldGetReservationAndReturn200() throws Exception {
        String body = """
                {"userId": "u1", "sku": "A", "quantity": 2}""";
        ResponseEntity<String> createResp = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders), String.class);
        String reservationId = objectMapper.readTree(createResp.getBody())
                .get("reservationId").asText();

        ResponseEntity<String> getResp = restTemplate.getForEntity(
                baseUrl() + "/api/reservations/" + reservationId, String.class);

        assertEquals(HttpStatus.OK, getResp.getStatusCode());
        assertEquals("RESERVED",
                objectMapper.readTree(getResp.getBody()).get("status").asText());
    }

    @Test
    void shouldReturn404ForUnknownReservationId() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/reservations/nonexistent-id", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // =========================================================================
    // GET /api/inventory
    // =========================================================================

    @Test
    void shouldGetAllInventory() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/inventory", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertTrue(json.isArray());
        assertEquals(3, json.size());
    }

    @Test
    void shouldGetSingleInventoryBySku() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/inventory/A", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("A", json.get("sku").asText());
        assertEquals(5, json.get("totalStock").asInt());
    }

    @Test
    void shouldReturn404ForUnknownInventorySku() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/api/inventory/UNKNOWN", String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // =========================================================================
    // Error response structure
    // =========================================================================

    @Test
    void shouldReturnStructuredErrors() throws Exception {
        String body = """
                {"userId": "u1", "sku": "UNKNOWN", "quantity": 1}""";

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/reservations",
                new HttpEntity<>(body, jsonHeaders),
                String.class);

        JsonNode json = objectMapper.readTree(response.getBody());
        assertTrue(json.has("status"));
        assertTrue(json.has("error"));
        assertTrue(json.has("message"));
        assertTrue(json.has("path"));
    }
}