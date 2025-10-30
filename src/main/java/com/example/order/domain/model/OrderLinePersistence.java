package com.example.order.domain.model;

import java.math.BigDecimal;

public record OrderLinePersistence(String productId, int qty, BigDecimal unitPrice) {}