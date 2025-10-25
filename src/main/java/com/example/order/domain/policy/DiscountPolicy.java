package com.example.order.domain.policy;

import java.math.BigDecimal;

import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public interface DiscountPolicy {
	/**
	 * baseAfterPrevious: 直前までの割引を反映した基準金額（この金額に対して本ポリシーを計算）
	 * 返り値: 本ポリシーで適用する割引「額」（>=0）
	 */
	BigDecimal discount(OrderRequest req,ProductRepository products,BigDecimal baseAfterPrevious);
}
