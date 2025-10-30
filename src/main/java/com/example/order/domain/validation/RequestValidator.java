package com.example.order.domain.validation;

import com.example.order.app.dto.OrderRequest;

public class RequestValidator {
	private RequestValidator() {
	}

	public static void validate(OrderRequest req) {
		if (req == null)
			throw new IllegalArgumentException("request must not be null");
		if (req.region() == null || req.region().isBlank())
			throw new IllegalArgumentException("region must not be blank");
		if (req.lines() == null || req.lines().isEmpty())
			throw new IllegalArgumentException("order lines must not be empty");
		for (var line : req.lines()) {
			if (line.qty() <= 0)
				throw new IllegalArgumentException("qty must be > 0");
		}
	}
}
