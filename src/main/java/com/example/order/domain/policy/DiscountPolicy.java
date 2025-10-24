package com.example.order.domain.policy;

import java.math.BigDecimal;

import com.example.order.dto.OrderRequest;
import com.example.order.port.outbound.ProductRepository;

public interface DiscountPolicy {
	/**
     * このポリシーが適用する割引額（>= 0）。
     * ここでは副作用なし（純粋計算）を前提にする。
     */
    BigDecimal discount(OrderRequest req, ProductRepository products);
}
