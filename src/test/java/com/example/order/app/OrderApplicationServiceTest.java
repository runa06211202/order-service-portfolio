package com.example.order.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.order.domain.model.OrderPersistenceModel;
import com.example.order.domain.model.Product;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.SaveOrderPort;

@ExtendWith(MockitoExtension.class)
public class OrderApplicationServiceTest {
	@Mock
	OrderService orderService;
	@Mock
	ProductRepository products;
	@Mock
	SaveOrderPort savePort;
	OrderApplicationService app;

	@BeforeEach
	void setUp() {
		app = new OrderApplicationService(orderService, products, savePort);
	}

	@Test
	@Tag("anchor")
	void saves_persistence_model_built_from_result_and_lines() {
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
				new OrderRequest.Line("P001", 2), new OrderRequest.Line("P002", 1)));
		when(products.findById("P001")).thenReturn(Optional.of(new Product("P001", "A", new BigDecimal("1000"))));
		when(products.findById("P002")).thenReturn(Optional.of(new Product("P002", "B", new BigDecimal("500"))));

		OrderResult result = new OrderResult(
				new BigDecimal("2500.00"),
				new BigDecimal("50.00"),
				new BigDecimal("2450.00"),
				new BigDecimal("245.00"),
				new BigDecimal("2695"),
				List.of());

		when(orderService.placeOrder(req)).thenReturn(result);
		when(savePort.save(any())).thenReturn("ORD-001");

		var id = app.placeAndSave(req);
		assertThat(id).isEqualTo("ORD-001");

		ArgumentCaptor<OrderPersistenceModel> captor = ArgumentCaptor.forClass(OrderPersistenceModel.class);
		verify(savePort).save(captor.capture());
		var m = captor.getValue();
		assertThat(m.netBefore()).isEqualByComparingTo("2500.00");
		assertThat(m.totalDiscount()).isEqualByComparingTo("50.00");
		assertThat(m.gross()).isEqualByComparingTo("2695");
		assertThat(m.lines()).hasSize(2);
	}

	@Test
	void does_not_save_when_orderService_throws() {
		var req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new OrderRequest.Line("NG", 1)));
		when(orderService.placeOrder(req)).thenThrow(new IllegalArgumentException("bad"));
		assertThatThrownBy(() -> app.placeAndSave(req)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(savePort);
	}
}
