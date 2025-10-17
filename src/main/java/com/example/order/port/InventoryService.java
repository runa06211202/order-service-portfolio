package com.example.order.port;

public interface InventoryService {
  void reserve(String productId, int qty);
}
