package com.example.order.step;

import java.math.BigDecimal;

import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.DiscountType;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class MultiItemDiscount implements DiscountPolicy {
	private static final BigDecimal RATE = new BigDecimal("0.02");

	@Override
	public BigDecimal discount(OrderRequest req, ProductRepository products, BigDecimal baseAfterPrevious) {
		long distinct = req.lines().stream().map(l -> l.productId()).distinct().count();
		if (distinct >= 3) {
			return baseAfterPrevious.multiply(RATE);
		}
		return BigDecimal.ZERO;
	}
	@Override
	public DiscountType type() {
	    return DiscountType.MULTI_ITEM;
	}
}
