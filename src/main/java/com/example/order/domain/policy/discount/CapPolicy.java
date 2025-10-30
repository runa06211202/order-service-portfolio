package com.example.order.domain.policy.discount;

import java.math.BigDecimal;

import com.example.order.app.dto.DiscountType;
import com.example.order.app.dto.OrderRequest;
import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.port.outbound.ProductRepository;

public class CapPolicy implements DiscountPolicy {
	private final BigDecimal rate; // 例: 0.30

	public CapPolicy(BigDecimal rate) {
		this.rate = rate;
	}

	@Override
	public BigDecimal discount(OrderRequest req, ProductRepository products, BigDecimal baseAfterPrevious) {
		// subtotal を再計算（純粋関数）
		BigDecimal subtotal = BigDecimal.ZERO;
		for (var line : req.lines()) {
			var p = products.findById(line.productId())
					.orElseThrow(() -> new IllegalArgumentException("product not found: " + line.productId()));
			subtotal = subtotal.add(p.price().multiply(BigDecimal.valueOf(line.qty())));
		}

		// ここまでに適用済みの合計割引 = subtotal - baseAfterPrevious
		BigDecimal sumSoFar = subtotal.subtract(baseAfterPrevious);
		BigDecimal capLimit = subtotal.multiply(rate);

		// すでに上限を超えていたら、超過分を「負の割引」で差し戻す（末尾専用の調整）
		if (sumSoFar.compareTo(capLimit) > 0) {
			return capLimit.subtract(sumSoFar); // 負の値
		}
		return BigDecimal.ZERO;
	}
	@Override
	// ADR-010
	public DiscountType type() {
	    return DiscountType.CAP;
	}
}
