package com.digithon.assignment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for deserializing inventory.json seed data.
 */
public class SeedProduct {

    @JsonProperty("sku")
    private String sku;

    @JsonProperty("name")
    private String name;

    @JsonProperty("totalStock")
    private int totalStock;

    @JsonProperty("purchaseLimit")
    private int purchaseLimit;

    public SeedProduct() {
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(int totalStock) {
        this.totalStock = totalStock;
    }

    public int getPurchaseLimit() {
        return purchaseLimit;
    }

    public void setPurchaseLimit(int purchaseLimit) {
        this.purchaseLimit = purchaseLimit;
    }
}