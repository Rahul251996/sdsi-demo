package com.digithon.assignment.service;

import com.digithon.assignment.domain.Product;
import com.digithon.assignment.dto.SeedData;
import com.digithon.assignment.dto.SeedProduct;
import com.digithon.assignment.exception.InvalidInputException;
import com.digithon.assignment.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class InventorySeedService {

    private static final Logger log = LoggerFactory.getLogger(InventorySeedService.class);

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    @Value("${inventory.seed-file:classpath:inventory.json}")
    private Resource seedFile;

    public InventorySeedService(ProductRepository productRepository, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Load initial inventory on application startup.
     * Idempotent: only inserts products that don't already exist.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadInitialInventory() {
        try {
            SeedData seedData = parseAndValidate();
            int inserted = 0;
            for (SeedProduct sp : seedData.getProducts()) {
                if (!productRepository.existsById(sp.getSku())) {
                    Product product = new Product(
                            sp.getSku(),
                            sp.getName(),
                            sp.getTotalStock(),
                            sp.getPurchaseLimit()
                    );
                    productRepository.save(product);
                    inserted++;
                    log.info("Seeded product: sku={}, name={}, totalStock={}, purchaseLimit={}",
                            sp.getSku(), sp.getName(), sp.getTotalStock(), sp.getPurchaseLimit());
                } else {
                    log.debug("Product already exists, skipping: sku={}", sp.getSku());
                }
            }
            log.info("Inventory seeding complete. {} new products inserted.", inserted);
        } catch (IOException e) {
            log.error("Failed to read inventory seed file: {}", e.getMessage());
            throw new InvalidInputException("Failed to read inventory seed file: " + e.getMessage());
        }
    }

    /**
     * Parse and validate the seed JSON file.
     */
    SeedData parseAndValidate() throws IOException {
        SeedData seedData;
        try (InputStream is = seedFile.getInputStream()) {
            seedData = objectMapper.readValue(is, SeedData.class);
        }

        if (seedData.getProducts() == null || seedData.getProducts().isEmpty()) {
            throw new InvalidInputException("Seed file must contain a non-empty 'products' array");
        }

        validateProducts(seedData.getProducts());
        return seedData;
    }

    /**
     * Validate all seed products before importing any.
     */
    private void validateProducts(List<SeedProduct> products) {
        Set<String> seenSkus = new HashSet<>();

        for (int i = 0; i < products.size(); i++) {
            SeedProduct sp = products.get(i);
            String prefix = "Product[" + i + "]";

            if (sp.getSku() == null || sp.getSku().isBlank()) {
                throw new InvalidInputException(prefix + ": sku is required and must not be blank");
            }
            if (seenSkus.contains(sp.getSku())) {
                throw new InvalidInputException(prefix + ": duplicate sku '" + sp.getSku() + "'");
            }
            seenSkus.add(sp.getSku());

            if (sp.getName() == null || sp.getName().isBlank()) {
                throw new InvalidInputException(prefix + ": name is required and must not be blank");
            }
            if (sp.getTotalStock() < 0) {
                throw new InvalidInputException(prefix + ": totalStock must be non-negative, got " + sp.getTotalStock());
            }
            if (sp.getPurchaseLimit() <= 0) {
                throw new InvalidInputException(prefix + ": purchaseLimit must be positive, got " + sp.getPurchaseLimit());
            }
        }
    }
}