package com.example.order.dto;

import java.math.RoundingMode;
import java.util.List;

public record OrderRequest(String region, RoundingMode mode, List<Line> lines) {
  public record Line(String productId, int qty) {}
}
