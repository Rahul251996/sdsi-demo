package com.digithon.assignment;

import com.digithon.assignment.TestConfig.FixedClock;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.repository.ProductRepository;
import com.digithon.assignment.repository.ReservationEventRepository;
import com.digithon.assignment.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests using Testcontainers MySQL.
 * Provides:
 * - A real MySQL 8.0 database per test class
 * - Controllable Clock via TestConfig.FixedClock
 * - DB cleanup before/after each test
 * - Inventory-consistency assertion helpers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestConfig.class)
public abstract class BaseIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected FixedClock clock;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected ReservationRepository reservationRepository;

    @Autowired
    protected ReservationEventRepository eventRepository;

    @BeforeEach
    void baseSetUp() {
        eventRepository.deleteAll();
        reservationRepository.deleteAll();
        productRepository.deleteAll();
        productRepository.flush();
        // Reset clock to fixed start time
        clock.setInstant(TestConfig.START);
    }

    @AfterEach
    void baseTearDown() {
        eventRepository.deleteAll();
        reservationRepository.deleteAll();
        productRepository.deleteAll();
    }

    // =========================================================================
    // Test fixture helpers
    // =========================================================================

    protected void seedStandardProducts() {
        productRepository.save(new Product("A", "Product A", 5, 2));
        productRepository.save(new Product("B", "Product B", 1, 1));
        productRepository.save(new Product("C", "Product C", 0, 1));
        productRepository.flush();
    }

    protected void seedProduct(String sku, String name, int totalStock, int purchaseLimit) {
        productRepository.save(new Product(sku, name, totalStock, purchaseLimit));
        productRepository.flush();
    }

    // =========================================================================
    // Inventory consistency assertions
    // =========================================================================

    protected void assertInventoryConsistent(Product p) {
        int sum = p.getAvailableStock() + p.getReservedStock() + p.getSoldStock();
        if (p.getTotalStock() != sum) {
            throw new AssertionError(String.format(
                    "Inventory not consistent for %s: total=%d, avail=%d, reserved=%d, sold=%d (sum=%d)",
                    p.getSku(), p.getTotalStock(), p.getAvailableStock(),
                    p.getReservedStock(), p.getSoldStock(), sum));
        }
        if (p.getAvailableStock() < 0) {
            throw new AssertionError("Negative available stock for " + p.getSku());
        }
        if (p.getReservedStock() < 0) {
            throw new AssertionError("Negative reserved stock for " + p.getSku());
        }
        if (p.getSoldStock() < 0) {
            throw new AssertionError("Negative sold stock for " + p.getSku());
        }
    }

    protected void assertAllInventoryConsistent() {
        productRepository.findAll().forEach(this::assertInventoryConsistent);
    }

    protected Product getProduct(String sku) {
        return productRepository.findById(sku)
                .orElseThrow(() -> new AssertionError("Product not found: " + sku));
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}