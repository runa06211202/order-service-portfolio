package com.example.order.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.order.domain.model.Product;
import com.example.order.domain.policy.DiscountCapPolicy;
import com.example.order.domain.policy.PercentCapPolicy;
import com.example.order.dto.DiscountType;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderRequest.Line;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;



public class OrderService {
  private final ProductRepository products;
  private final InventoryService inventory;
  private final TaxCalculator tax;
  private final DiscountCapPolicy capPolicy;
  private static final BigDecimal VOLUME_DISCOUNT_RATE = new BigDecimal("0.05");
  private static final BigDecimal MULTI_ITEM_DISCOUNT_RATE = new BigDecimal("0.02");
  private static final BigDecimal HIGH_AMOUNT_DISCOUNT_RATE = new BigDecimal("0.03");
  private static final int MULTI_ITEM_DISCOUNT_NUMBER_OF_LINES = 3;
  private static final BigDecimal HIGH_AMOUNT_DISCOUNT_APPLY_NET = new BigDecimal("100000");

  public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax, DiscountCapPolicy capPolicy) {
    this.products = products;
    this.inventory = inventory;
    this.tax = tax;
    this.capPolicy = capPolicy;
  }
  
  public OrderService(ProductRepository products, InventoryService inventory, TaxCalculator tax) {
	  this(products, inventory, tax, new PercentCapPolicy(new BigDecimal("0.30"))); // デフォルト30%
  }

  public OrderResult placeOrder(OrderRequest req) {
	  // Ref: 後で全体的にメソッド分割
	  // 引数チェック
	  if(req == null || req.lines() == null || req.lines().isEmpty()) {
		  throw new IllegalArgumentException(notNullOrEmptyMsg("lines"));
	  }
	  for(Line line : req.lines()) {
		  if(line.qty() <= 0) {
			  throw new IllegalArgumentException(notZeroOrMinus("qty"));
		  }
	  }
	  if(req.region() == null || req.region().isBlank()) {
		  throw new IllegalArgumentException(notNullOrBlankStrings("region"));
	  }
	  
	  BigDecimal orderNetBeforeDiscount = BigDecimal.ZERO;
	  BigDecimal volumeDiscount = BigDecimal.ZERO;
	  List<DiscountType> appliedDiscounts = new ArrayList<DiscountType>();
	  for(Line line : req.lines()) {
		  // Optional<Product>をここでunwrap
		  Product product = products.findById(line.productId())
				  .orElseThrow(() -> new IllegalArgumentException(notFindProduct(line.productId())));

		  BigDecimal lineSubtotal = product.unitPrice()
				  .multiply(BigDecimal.valueOf(line.qty()));

		  orderNetBeforeDiscount = orderNetBeforeDiscount.add(lineSubtotal);
		  
		  // VOLUME割引
		  if(line.qty() >= 10) {
			  volumeDiscount = volumeDiscount.add(lineSubtotal
					  .multiply(VOLUME_DISCOUNT_RATE));
		  }		  
	  }

	  if(volumeDiscount.compareTo(BigDecimal.ZERO) == 1) {
		  appliedDiscounts.add(DiscountType.VOLUME);
	  }
	  BigDecimal subtotalVolumeDiscount = orderNetBeforeDiscount.subtract(volumeDiscount);
	  
	  BigDecimal multiItemDiscount = BigDecimal.ZERO;
	  BigDecimal subtotalMultiItemDiscount = BigDecimal.ZERO;

	  // MULTI_ITEM割引
	  List<Line> distinctLines = req.lines().stream()
			  .distinct()
			  .collect(Collectors.toList());
	  if(distinctLines.size() >= MULTI_ITEM_DISCOUNT_NUMBER_OF_LINES) {
		  multiItemDiscount = subtotalVolumeDiscount.multiply(MULTI_ITEM_DISCOUNT_RATE);
	  }
	  if(multiItemDiscount.compareTo(BigDecimal.ZERO) == 1) {
		  appliedDiscounts.add(DiscountType.MULTI_ITEM);
	  }
	  subtotalMultiItemDiscount = subtotalVolumeDiscount.subtract(multiItemDiscount);

	  BigDecimal highAmountDiscount = BigDecimal.ZERO;
	  
	  // HIGH_AMOUNT割引
	  if(subtotalMultiItemDiscount.compareTo(HIGH_AMOUNT_DISCOUNT_APPLY_NET) >= 0) {
		  highAmountDiscount = subtotalMultiItemDiscount.multiply(HIGH_AMOUNT_DISCOUNT_RATE);
	  }
	  if(highAmountDiscount.compareTo(BigDecimal.ZERO) == 1) {
		  appliedDiscounts.add(DiscountType.HIGH_AMOUNT);
	  }

	  BigDecimal totalNetBeforeDiscount = BigDecimal.ZERO;
	  BigDecimal totalDiscount  = BigDecimal.ZERO;
	  BigDecimal totalNetAfterDiscount = BigDecimal.ZERO;
	  BigDecimal totalTax = BigDecimal.ZERO;
	  BigDecimal totalGross = BigDecimal.ZERO;

	  totalNetBeforeDiscount = orderNetBeforeDiscount;
	  BigDecimal rawTotalDiscount  = BigDecimal.ZERO.add(volumeDiscount).add(multiItemDiscount).add(highAmountDiscount);

	  // Cap適用
	  BigDecimal cappedDiscount = capPolicy.apply(totalNetBeforeDiscount, rawTotalDiscount);
	  totalDiscount = cappedDiscount;
	  totalNetAfterDiscount = orderNetBeforeDiscount.subtract(cappedDiscount);
	  
	  //在庫確認
	  for(Line line : req.lines()) {
		  inventory.reserve(line.productId(), line.qty());
	  }

	  RoundingMode modeOrDefault = (req.mode() == null) ? RoundingMode.HALF_UP : req.mode();
	  //税計算
	  totalTax = totalTax.add(tax.calcTaxAmount(totalNetAfterDiscount, req.region(), modeOrDefault));
	  totalGross = totalGross.add(tax.addTax(totalNetAfterDiscount, req.region(), modeOrDefault));

	  OrderResult orderResult = new OrderResult(orderNetBeforeDiscount.setScale(2, RoundingMode.HALF_UP), totalDiscount.setScale(2, RoundingMode.HALF_UP),
			  totalNetAfterDiscount.setScale(2, RoundingMode.HALF_UP), totalTax.setScale(2, RoundingMode.HALF_UP),
			  totalGross.setScale(0, RoundingMode.HALF_UP), appliedDiscounts);

	  return orderResult;
  }

  // エラーメッセージ定義
  private static String notNullOrEmptyMsg(String fieldName) {
	  return fieldName + " must not be null or empty";
  }

  private static String notZeroOrMinus(String fieldName) {
	  return fieldName + " must not be zero or minus";
  }

  private static String notNullOrBlankStrings(String fieldName) {
	  return fieldName + " must not be null or blank strings";
  }

  private static String notFindProduct(String productId) {
	  return "product not found: " + productId;
  }
}
