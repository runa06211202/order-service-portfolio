package com.example.order.port.outbound;

public interface InventoryService {
  void reserve(String productId, int qty);
  boolean checkAvailable(String productId, int qty); // 追加(ADR-007)
}
