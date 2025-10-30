package com.example.order.app;

import java.util.List;

import com.example.order.domain.model.OrderLinePersistence;
import com.example.order.domain.model.OrderPersistenceModel;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.SaveOrderPort;

public class OrderApplicationService {
	private final OrderService orderService;
	private final ProductRepository products;
	private final SaveOrderPort savePort;

	public OrderApplicationService(OrderService orderService, ProductRepository products, SaveOrderPort savePort) {
		this.orderService = orderService;
		this.products = products;
		this.savePort = savePort;
	}

	public String placeAndSave(OrderRequest req) {
		// 計算（副作用はOrderServiceに準拠。在庫予約・税は既にOrderServiceが面倒みてる現状でOK）
		OrderResult result = orderService.placeOrder(req);

		// 保存用マッピング（単価はProductRepositoryから引いて行を復元）
		List<OrderLinePersistence> lines = req.lines().stream().map(l -> {
			var p = products.findById(l.productId())
					.orElseThrow(() -> new IllegalArgumentException("product not found: " + l.productId()));
			return new OrderLinePersistence(p.id(), l.qty(), p.price());
		}).toList();

		OrderPersistenceModel model = new OrderPersistenceModel(
				req.region(), lines,
				result.totalNetBeforeDiscount(),
				result.totalDiscount(),
				result.totalNetAfterDiscount(),
				result.totalTax(),
				result.totalGross(),
				result.appliedDiscounts());
		return savePort.save(model);
	}

}
