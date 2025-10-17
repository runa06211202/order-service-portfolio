package com.example.order.app;

import java.math.BigDecimal;
import java.util.List;

import com.example.order.domain.policy.DiscountCapPolicy;
import com.example.order.domain.policy.PercentCapPolicy;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;

public class OrderService {

	private final ProductRepository products;
	private final InventoryService inventory;
	private final TaxCalculator tax;
	private final PercentCapPolicy policy;
	private final DiscountCapPolicy capPolicy;

	public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax, DiscountCapPolicy capPolicy) {
	    this.products = products;
	    this.inventory = inventory;
	    this.tax = tax;
		this.policy = null;
	    this.capPolicy = capPolicy;
	  }
	  
	  public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax) {
		  this(products, inventory, tax, new PercentCapPolicy(new BigDecimal("0.30"))); // デフォルト30%
	  }

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
