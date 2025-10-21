package com.example.order.domain.model;

import java.math.BigDecimal;

public record Product(String id, String name, BigDecimal unitPrice) {}
