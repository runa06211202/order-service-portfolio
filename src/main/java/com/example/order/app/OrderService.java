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

	public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax,
			DiscountCapPolicy capPolicy) {
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

		/*
		 *  TODO: 割引や税計算を導入してもこの呼び出し順序を維持すること
		 *  (validate → calculateDiscounts → calculateTax → reserveInventory → saveOrder)
		 */
		validateRequest(req);
		validateQty(req.lines());

		// 在庫呼び出しをメソッド化
		reserveInventory(req.lines());

		// TODO: 金額計算が始まったら Money/Policy 抽出（丸め規約の分散を解消）
		// まだ中身は実装してない（仮）
		return emptyResult();
	}

	private OrderResult emptyResult() {
		return new OrderResult(
				BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				BigDecimal.ZERO, BigDecimal.ZERO, List.of());
	}

	private void validateRequest(OrderRequest req) {
		// TODO: メッセージ仕様が増えたら MessageBuilder へ委譲
		if (req == null)
			throw new IllegalArgumentException("orderRequest must not be null");
		if (req.lines() == null)
			throw new IllegalArgumentException("lines must not be null");
		if (req.lines().isEmpty())
			throw new IllegalArgumentException("lines must not be empty");
	}

	private void validateQty(List<OrderRequest.Line> lines) {
		for (var line : lines) {
			if (line.qty() <= 0)
				throw new IllegalArgumentException("qty must be > 0");
		}
	}

	// 在庫呼び出し部分を抽出
	private void reserveInventory(List<OrderRequest.Line> lines) {
		for (var line : lines) {
			inventory.reserve(line.productId(), line.qty());
		}
	}
}
