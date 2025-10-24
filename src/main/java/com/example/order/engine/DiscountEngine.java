package com.example.order.engine;

import java.math.BigDecimal;
import java.util.List;

import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class DiscountEngine {
	private DiscountEngine() {
	}

	public static BigDecimal applyAll(List<DiscountPolicy> policies,OrderRequest req,ProductRepository products) {
		BigDecimal sum = BigDecimal.ZERO;
		for (var policy : policies) {
			BigDecimal d = policy.discount(req, products);
			if (d.signum() > 0) {
				sum = sum.add(d);
			}
		}
		return sum;
	}
}
