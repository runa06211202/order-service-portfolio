package com.example.order.port;

import java.math.BigDecimal;
import java.math.RoundingMode;

public interface TaxCalculator {
  BigDecimal calcTaxAmount(BigDecimal net, String region, RoundingMode mode); // ADR-002
  BigDecimal addTax(BigDecimal net, String region, RoundingMode mode);
}
