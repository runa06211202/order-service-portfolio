package com.example.order.app;

import java.math.BigDecimal;
import java.util.List;

import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResult;

public class OrderService {

  public OrderResult placeOrder(OrderRequest req) {
	  // まだ中身は実装してない（仮）
	  return new OrderResult(
			  BigDecimal.ZERO,
			  BigDecimal.ZERO,
			  BigDecimal.ZERO,
			  BigDecimal.ZERO,
			  BigDecimal.ZERO,
			  List.of() // 空の割引リスト
			  );
  }
}
