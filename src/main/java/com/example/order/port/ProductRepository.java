package com.example.order.port;

import com.example.order.domain.Product;
import java.util.Optional;

public interface ProductRepository {
  Optional<Product> findById(String productId);
}
