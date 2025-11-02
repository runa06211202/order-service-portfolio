package com.example.order.infrastructure.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.example.order.domain.model.Product;
import com.example.order.port.outbound.ProductRepository;

public class ProductRepositoryInMemory implements ProductRepository{
	private final Map<String, Product> store;
	
	public ProductRepositoryInMemory(Map<String, Product> preload) {
        // 価格をscale=2に正規化してコピー
        this.store = preload.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> normalize(e.getValue())
                ));
    }
	
	@Override
    public Optional<Product> findById(String productId) {
        return Optional.ofNullable(store.get(productId));
    }
	
	private static Product normalize(Product p) {
        BigDecimal price = p.price().setScale(2, RoundingMode.HALF_UP);
        return new Product(p.id(), p.name(), price);
    }
}
