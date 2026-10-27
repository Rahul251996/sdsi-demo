package com.digithon.assignment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wrapper for deserializing the products array from inventory.json.
 */
public class SeedData {

    @JsonProperty("products")
    private List<SeedProduct> products;

    public SeedData() {
    }

    public List<SeedProduct> getProducts() {
        return products;
    }

    public void setProducts(List<SeedProduct> products) {
        this.products = products;
    }
}