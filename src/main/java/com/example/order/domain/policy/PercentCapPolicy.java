package com.example.order.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PercentCapPolicy implements DiscountCapPolicy{
	  private final BigDecimal capRate; // 0.30
	  public PercentCapPolicy(BigDecimal capRate) { this.capRate = capRate; }
	  @Override
	  public BigDecimal apply(BigDecimal subtotal, BigDecimal rawDiscount) {
	    BigDecimal cap = subtotal.multiply(capRate).setScale(2, RoundingMode.HALF_UP);
	    return rawDiscount.min(cap);
	  }
}
