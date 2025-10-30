package com.example.order.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.order.app.checker.InventoryChecker;
import com.example.order.app.dto.DiscountResult;
import com.example.order.app.dto.OrderRequest;
import com.example.order.app.dto.OrderResult;
import com.example.order.domain.model.Product;
import com.example.order.domain.policy.DiscountPolicy;
import com.example.order.domain.policy.discount.CapPolicy;
import com.example.order.domain.policy.discount.HighAmountDiscount;
import com.example.order.domain.policy.discount.MultiItemDiscount;
import com.example.order.domain.policy.discount.VolumeDiscount;
import com.example.order.domain.service.DiscountEngine;
import com.example.order.domain.validation.RequestValidator;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;

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

		RequestValidator.validate(req);
		InventoryChecker.ensureAvailable(inventory, req); // ← 追加（ここで早期return）（ADR-007）

		// --- 計算ステップ ---
		BigDecimal totalNetBeforeDiscount = computeSubtotal(req);
		final DiscountResult discountResult = DiscountEngine.applyInOrder(discountPolicies, req, products,
				totalNetBeforeDiscount);
		BigDecimal totalDiscount = discountResult.total();
		BigDecimal totalNetAfterDiscount = totalNetBeforeDiscount.subtract(totalDiscount);

		// 丸め既定：null なら HALF_UP
		RoundingMode mode = (req.mode() != null) ? req.mode() : RoundingMode.HALF_UP;
		BigDecimal totalTax = tax.calcTaxAmount(totalNetAfterDiscount, req.region(), mode); // 丸めモード使用

		BigDecimal totalGross = tax.addTax(totalNetAfterDiscount, req.region(), mode);

		// 在庫在庫確保は最後(ADR-006)
		InventoryChecker.reserveAll(inventory, req);

		// スケールの正規化
		return new OrderResult(
				totalNetBeforeDiscount.setScale(2, RoundingMode.HALF_UP),
				totalDiscount.setScale(2, RoundingMode.HALF_UP),
				totalNetAfterDiscount.setScale(2, RoundingMode.HALF_UP), // ADR-008
				totalTax.setScale(2, RoundingMode.HALF_UP),
				totalGross.setScale(0, RoundingMode.HALF_UP),
				discountResult.applied());
	}

	// 小計計算
	private BigDecimal computeSubtotal(OrderRequest req) {
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
}
