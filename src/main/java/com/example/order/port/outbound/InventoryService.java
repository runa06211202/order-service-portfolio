package com.example.order.port.outbound;

public interface InventoryService {
  void reserve(String productId, int qty);
}
