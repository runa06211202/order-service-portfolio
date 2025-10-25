package com.example.order.step;

import java.math.BigDecimal;

import com.example.order.domain.model.Product;
import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class VolumeDiscount implements DiscountPolicy {
	private static final BigDecimal RATE = new BigDecimal("0.05");

	@Override
	public BigDecimal discount(OrderRequest req, ProductRepository products, BigDecimal baseAfterPrevious) {
		BigDecimal total = BigDecimal.ZERO;

		for (var line : req.lines()) {
			if (line.qty() >= 10) {
				Product p = products.findById(line.productId())
						.orElseThrow(() -> new IllegalArgumentException("product not found: " + line.productId()));
				BigDecimal lineAmount = p.price().multiply(BigDecimal.valueOf(line.qty()));
				total = total.add(lineAmount.multiply(RATE));
			}
		}
		return total;
	}
}
