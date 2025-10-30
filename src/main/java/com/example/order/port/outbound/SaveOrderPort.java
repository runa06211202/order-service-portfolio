package com.example.order.port.outbound;

import com.example.order.domain.model.OrderPersistenceModel;

public interface SaveOrderPort {
	// 最小はID返しでOK。必要ならバージョンやステータスも拡張
	String save(OrderPersistenceModel order);
}
