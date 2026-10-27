package com.digithon.assignment;

import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.repository.ProductRepository;
import com.digithon.assignment.repository.ReservationEventRepository;
import com.digithon.assignment.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(TestConfig.class)
public abstract class BaseIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
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

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected ReservationRepository reservationRepository;

    @Autowired
    protected ReservationEventRepository eventRepository;

    @BeforeEach
    void baseSetUp() {
        // Clean and re-seed data for each test
        eventRepository.deleteAll();
        reservationRepository.deleteAll();
        productRepository.deleteAll();
    }

    @AfterEach
    void baseTearDown() {
        eventRepository.deleteAll();
        reservationRepository.deleteAll();
        productRepository.deleteAll();
    }

    protected void seedStandardProducts() {
        productRepository.save(new Product("A", "Product A", 5, 2));
        productRepository.save(new Product("B", "Product B", 1, 1));
        productRepository.save(new Product("C", "Product C", 0, 1));
    }

    protected Reservation createReservation(String userId, String sku, int qty,
                                              Instant expiresAt) {
        Reservation r = new Reservation(
                UUID.randomUUID().toString(), userId, sku, qty,
                ReservationStatus.RESERVED, expiresAt);
        return reservationRepository.save(r);
    }

    protected void assertInventoryConsistent(Product p) {
        int calc = p.getAvailableStock() + p.getReservedStock() + p.getSoldStock();
        if (p.getTotalStock() != calc) {
            throw new AssertionError(String.format(
                    "Inventory not consistent for %s: total=%d, available=%d, reserved=%d, sold=%d (sum=%d)",
                    p.getSku(), p.getTotalStock(), p.getAvailableStock(),
                    p.getReservedStock(), p.getSoldStock(), calc));
        }
        if (p.getAvailableStock() < 0) throw new AssertionError("Available stock negative for " + p.getSku());
        if (p.getReservedStock() < 0) throw new AssertionError("Reserved stock negative for " + p.getSku());
        if (p.getSoldStock() < 0) throw new AssertionError("Sold stock negative for " + p.getSku());
    }

    protected void assertAllInventoryConsistent() {
        productRepository.findAll().forEach(this::assertInventoryConsistent);
    }
}