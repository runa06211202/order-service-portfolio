package com.example.order.port.outbound;

import java.util.Optional;

import com.example.order.domain.model.Product;

public interface ProductRepository {
  Optional<Product> findById(String productId);
}
