package com.example.order.app;

import com.example.order.dto.OrderRequest;
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
		var result = orderService.placeOrder(req);
		return null;
	}
}
