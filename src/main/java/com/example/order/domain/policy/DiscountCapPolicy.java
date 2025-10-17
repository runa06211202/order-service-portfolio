package com.example.order.domain.policy;

import java.math.BigDecimal;

public interface DiscountCapPolicy {
	BigDecimal apply(BigDecimal subtotalBeforeDiscount, BigDecimal totalDiscount); // 2桁scaleで返す（ADR-001）
}
