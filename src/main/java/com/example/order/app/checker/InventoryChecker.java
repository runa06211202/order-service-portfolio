package com.example.order.app.checker;

import com.example.order.app.dto.OrderRequest;
import com.example.order.port.outbound.InventoryService;

public class InventoryChecker {
	private InventoryChecker() {
	}

	// 在庫可用性チェック(ADR-007)
	public static void ensureAvailable(InventoryService inventory, OrderRequest req) {
		for (var line : req.lines()) {
			boolean ok = inventory.checkAvailable(line.productId(), line.qty());
			if (!ok)
				throw new IllegalStateException("no stock for product " + line.productId());
		}
	}

	public static void reserveAll(InventoryService inventory, OrderRequest req) {
		for (var line : req.lines()) {
			inventory.reserve(line.productId(), line.qty());
		}
	}
}
