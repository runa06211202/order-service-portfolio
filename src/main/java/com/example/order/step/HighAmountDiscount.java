package com.example.order.step;

import java.math.BigDecimal;

import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class HighAmountDiscount implements DiscountPolicy {
	private static final BigDecimal THRESHOLD = new BigDecimal("100000");
	private static final BigDecimal RATE = new BigDecimal("0.03");

	@Override
	public BigDecimal discount(OrderRequest req,
			ProductRepository products,
			BigDecimal baseAfterPrevious) {
		if (baseAfterPrevious.compareTo(THRESHOLD) >= 0) {
			return baseAfterPrevious.multiply(RATE);
		}
		return BigDecimal.ZERO;
	}
}
