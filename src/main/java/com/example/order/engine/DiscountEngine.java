package com.example.order.engine;

import java.math.BigDecimal;
import java.util.List;

import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class DiscountEngine {
	private DiscountEngine() {
	}

	/*
	 * 順序依存
	 * 次ポリシーは全ポリシーの割引金額分を「引いた後」を基準に計算
	 */
	public static BigDecimal applyInOrder(List<DiscountPolicy> policies, OrderRequest req, ProductRepository products,
			BigDecimal subtotal) {
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal base = subtotal;

		for (var p : policies) {
			BigDecimal d = p.discount(req, products, base);
			total = total.add(d);
			base  = base.subtract(d);
		}
		return total;
	}

}
