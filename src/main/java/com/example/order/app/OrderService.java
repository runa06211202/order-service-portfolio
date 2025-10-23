package com.example.order.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.order.domain.model.Product;
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

		validateRequest(req);
		validateQty(req.lines());
		ensureAvailability(req.lines());     // ← 追加（ここで早期return）（ADR-007）

		// --- 計算ステップ ---
		BigDecimal netBeforeDiscount = calculateSubtotal(req);
		BigDecimal discount = calculateDiscount(netBeforeDiscount); // 今は常に0
		BigDecimal netAfterDiscount = netBeforeDiscount.subtract(discount);
		BigDecimal taxAmount = calculateTax(netAfterDiscount, req.region());
		BigDecimal gross = netAfterDiscount.add(taxAmount);

		// 在庫在庫確保は最後(ADR-006)
		reserveInventory(req.lines());

		return new OrderResult(
		        netBeforeDiscount,
		        discount,
		        netAfterDiscount,
		        taxAmount,
		        gross,
		        List.of() // appliedDiscounts は後で拡張
		    );
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

	// 在庫可用性チェック(ADR-007)
	private void ensureAvailability(List<OrderRequest.Line> lines) {
	    for (var line : lines) {
	        if (!inventory.checkAvailable(line.productId(), line.qty())) {
	            throw new RuntimeException("out of stock: " + line.productId());
	        }
	    }
	}

	// 小計計算
	private BigDecimal calculateSubtotal(OrderRequest req) {
		return req.lines().stream()
				.map(this::lineToAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	// Optional<Product>の中身が空の場合IAEをThrow、行計算自体を行わない(ADR-003)
	private BigDecimal lineToAmount(OrderRequest.Line line) {
		Product p = products.findById(line.productId())
				.orElseThrow(() -> new IllegalArgumentException(
						"product not found: " + line.productId()));
		return p.price().multiply(BigDecimal.valueOf(line.qty()));
	}

	// 割引処理
	private BigDecimal calculateDiscount(BigDecimal subtotal) {
		return BigDecimal.ZERO; // TODO: クーポンなど導入時に拡張
	}

	// 税計算
	private BigDecimal calculateTax(BigDecimal netAfterDiscount, String region) {
		var rate = tax.calculate(netAfterDiscount, region);
		return netAfterDiscount.multiply(rate).setScale(0, RoundingMode.DOWN); // 小数切捨て
	}

	// 在庫確保
	private void reserveInventory(List<OrderRequest.Line> lines) {
		for (var line : lines) {
			inventory.reserve(line.productId(), line.qty());
		}
	}
}
