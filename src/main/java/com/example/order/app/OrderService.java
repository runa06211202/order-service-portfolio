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
		// TODO: guardの重複が増えたら validateRequest(...) に抽出
		// qty ガードだけ（linesチェックは次サイクルで扱う）
		for (var line : req.lines()) {                 // ※ anchorのNormal/Abnormalはlinesを渡してる前提
			if (line.qty() <= 0) {
				// TODO: メッセージ仕様が増えたら MessageBuilder へ委譲
				throw new IllegalArgumentException("qty must be > 0");
			}
		}
		
		// 在庫呼び出し（例外は伝播）
		  for (var line : req.lines()) {
		    inventory.reserve(line.productId(), line.qty());
		  }

		// TODO: 金額計算が肥大したら Tax/Discount を純粋関数(or Money)へ
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
