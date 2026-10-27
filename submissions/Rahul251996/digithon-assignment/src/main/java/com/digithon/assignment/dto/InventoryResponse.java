package com.digithon.assignment.dto;

import com.digithon.assignment.domain.Product;

public class InventoryResponse {

    private String sku;
    private String name;
    private int totalStock;
    private int available;
    private int reserved;
    private int sold;
    private int purchaseLimit;

    public InventoryResponse() {
    }

    public static InventoryResponse from(Product p) {
        InventoryResponse resp = new InventoryResponse();
        resp.sku = p.getSku();
        resp.name = p.getName();
        resp.totalStock = p.getTotalStock();
        resp.available = p.getAvailableStock();
        resp.reserved = p.getReservedStock();
        resp.sold = p.getSoldStock();
        resp.purchaseLimit = p.getPurchaseLimit();
        return resp;
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

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public int getSold() {
        return sold;
    }

    public void setSold(int sold) {
        this.sold = sold;
    }

    public int getPurchaseLimit() {
        return purchaseLimit;
    }

    public void setPurchaseLimit(int purchaseLimit) {
        this.purchaseLimit = purchaseLimit;
    }
}