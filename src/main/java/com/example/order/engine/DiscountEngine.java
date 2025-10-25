package com.example.order.engine;

import java.math.BigDecimal;
import java.util.List;

import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public class DiscountEngine {
	private DiscountEngine() {
	}

	public static BigDecimal applyInOrder(List<DiscountPolicy> policies,OrderRequest req,ProductRepository products, BigDecimal subtotal) {
		BigDecimal total = BigDecimal.ZERO;
	    BigDecimal base  = subtotal; // 直前までの「割引後基準」

	    for (var p : policies) {
	        BigDecimal d = p.discount(req, products, base);
	        if (d.signum() > 0) {
	          total = total.add(d);
	          base  = base.subtract(d); // 次ポリシーは「引いた後」を基準に計算
	        }
	      }
		return total;
	}

}
