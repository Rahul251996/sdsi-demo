package com.digithon.assignment.controller;

import com.digithon.assignment.dto.InventoryResponse;
import com.digithon.assignment.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * M5: Get all inventory.
     */
    @GetMapping
    public ResponseEntity<List<InventoryResponse>> getAllInventory() {
        log.debug("Get all inventory");
        List<InventoryResponse> inventory = inventoryService.getAllInventory();
        return ResponseEntity.ok(inventory);
    }

    /**
     * M5: Get inventory for a specific SKU.
     */
    @GetMapping("/{sku}")
    public ResponseEntity<InventoryResponse> getInventoryBySku(@PathVariable String sku) {
        log.debug("Get inventory for sku={}", sku);
        InventoryResponse inventory = inventoryService.getInventoryBySku(sku);
        return ResponseEntity.ok(inventory);
    }
}