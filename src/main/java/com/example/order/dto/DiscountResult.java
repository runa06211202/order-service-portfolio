package com.example.order.dto;

import java.math.BigDecimal;
import java.util.List;

public record DiscountResult(BigDecimal total, List<DiscountType> applied) {}