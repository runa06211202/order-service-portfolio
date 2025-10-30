package com.example.order.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.order.domain.model.Product;
import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.dto.DiscountResult;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResult;
import com.example.order.engine.DiscountEngine;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;
import com.example.order.step.CapPolicy;
import com.example.order.step.HighAmountDiscount;
import com.example.order.step.MultiItemDiscount;
import com.example.order.step.VolumeDiscount;

public class OrderService {

	private final ProductRepository products;
	private final InventoryService inventory;
	private final TaxCalculator tax;

	// 追加：割引ポリシー群（今は Volume のみ）
	private final List<DiscountPolicy> discountPolicies;

	public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax) {
		this.products = products;
		this.inventory = inventory;
		this.tax = tax;
		this.discountPolicies = List.of(
				new VolumeDiscount(), // 1. VOLUME
				new MultiItemDiscount(), // 2. MULTI_ITEM
				new HighAmountDiscount(), // 3. HIGH_AMOUNT
				new CapPolicy(new BigDecimal("0.30")) // cap 30%固定
		);
	}

	// capポリシー注入用
	public OrderService(ProductRepository products,
			InventoryService inventory,
			TaxCalculator tax,
			List<DiscountPolicy> policies) {
		this.products = products;
		this.inventory = inventory;
		this.tax = tax;
		this.discountPolicies = List.copyOf(policies);
	}

	public OrderResult placeOrder(OrderRequest req) {

		validateRequest(req);
		validateQty(req.lines());
		ensureAvailability(req.lines()); // ← 追加（ここで早期return）（ADR-007）

		// --- 計算ステップ ---
		BigDecimal totalNetBeforeDiscount = calculateSubtotal(req);
		DiscountResult discountResult = DiscountEngine.applyInOrder(discountPolicies, req, products,
				totalNetBeforeDiscount);
		BigDecimal totalDiscount = discountResult.total();
		BigDecimal totalNetAfterDiscount = totalNetBeforeDiscount.subtract(totalDiscount);

		// 丸め既定：null なら HALF_UP
	    RoundingMode mode = (req.mode() != null) ? req.mode() : RoundingMode.HALF_UP;
		BigDecimal totalTax = tax.calcTaxAmount(totalNetAfterDiscount, req.region(), mode); // 丸めモード使用

		BigDecimal totalGross = tax.addTax(totalNetAfterDiscount, req.region(), mode);

		// 在庫在庫確保は最後(ADR-006)
		reserveInventory(req.lines());
		
		// スケールの正規化
		totalNetBeforeDiscount = totalNetBeforeDiscount.setScale(2, RoundingMode.HALF_UP);
		totalDiscount = totalDiscount.setScale(2, RoundingMode.HALF_UP);
		totalNetAfterDiscount = totalNetAfterDiscount.setScale(2, RoundingMode.HALF_UP);
		totalTax = totalTax.setScale(2, RoundingMode.HALF_UP);
		totalGross = totalGross.setScale(0, RoundingMode.HALF_UP);

		return new OrderResult(
				totalNetBeforeDiscount,
				totalDiscount,
				totalNetAfterDiscount, // ADR-008
				totalTax,
				totalGross,
				discountResult.applied());
	}

	private void validateRequest(OrderRequest req) {
		// TODO: メッセージ仕様が増えたら MessageBuilder へ委譲
		if (req == null)
			throw new IllegalArgumentException("orderRequest must not be null");
		var region = req.region();
		if (region == null || region.isBlank()) {
		    throw new IllegalArgumentException("region must not be blank");
		  }
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

	// 在庫確保
	private void reserveInventory(List<OrderRequest.Line> lines) {
		for (var line : lines) {
			inventory.reserve(line.productId(), line.qty());
		}
	}
}
