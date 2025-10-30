package com.example.order.domain.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.example.order.app.dto.DiscountResult;
import com.example.order.app.dto.DiscountType;
import com.example.order.app.dto.OrderRequest;
import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.port.outbound.ProductRepository;

public class DiscountEngine {
	private DiscountEngine() {
	}

	/*
	 * 順序依存
	 * 次ポリシーは全ポリシーの割引金額分を「引いた後」を基準に計算
	 */
	public static DiscountResult applyInOrder(List<DiscountPolicy> policies, OrderRequest req, ProductRepository products,
			BigDecimal subtotal) {
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal base = subtotal;
		List<DiscountType> applied = new ArrayList<>();

		for (var p : policies) {
			BigDecimal d = p.discount(req, products, base);
			if (d.compareTo(BigDecimal.ZERO) != 0) { // ← 非ゼロなら適用(ADR-010)
		        applied.add(p.type());
		    }
			total = total.add(d);
			base  = base.subtract(d);
		}
		return new DiscountResult(total, applied);
	}

}
