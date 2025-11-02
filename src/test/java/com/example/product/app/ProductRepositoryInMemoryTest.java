package com.example.product.app;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.order.domain.model.Product;
import com.example.order.port.outbound.ProductRepository;

public class ProductRepositoryInMemoryTest {

	@Test
	@Tag("anchor")
	@DisplayName("findByIdで商品が見付かった場合Productを返却する")
	void returns_present_when_found() {
		Product apple = new Product("P001", "Apple", new BigDecimal("1000.00"));
		ProductRepository repo = new ProductRepositoryInMemory(Map.of("P001", apple));

		assertThat(repo.findById("P001")).isPresent()
				.get().extracting(Product::name)
				.isEqualTo("Apple");
	}

	@Test
	@Tag("anchor")
	@DisplayName("findByIdで商品が見付からなかった場合NOPEを返却する")
	void returns_empty_when_not_found() {
		ProductRepository repo = new ProductRepositoryInMemory(Map.of());
		assertThat(repo.findById("NOPE")).isEmpty();
	}

	@Test
	@Tag("regression")
	@DisplayName("findByIdで返却される商品価格のスケールが2であることを確認")
	void normalizes_price_scale_to_2() {
		Product orange = new Product("P002", "Orange", new BigDecimal("200"));
		ProductRepository repo = new ProductRepositoryInMemory(Map.of("P002", orange));

		var p = repo.findById("P002").orElseThrow();
		assertThat(p.price().scale()).isEqualTo(2);
		assertThat(p.price()).isEqualByComparingTo("200.00");
	}
}
