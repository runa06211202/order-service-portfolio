package com.example.order.domain.model;

import com.example.order.dto.DiscountType;

public record OrderPersistenceModel(
		  String region,
		  java.util.List<OrderLinePersistence> lines,
		  java.math.BigDecimal netBefore,
		  java.math.BigDecimal totalDiscount,
		  java.math.BigDecimal netAfter,
		  java.math.BigDecimal totalTax,
		  java.math.BigDecimal gross,
		  java.util.List<DiscountType> appliedDiscounts
		) {}