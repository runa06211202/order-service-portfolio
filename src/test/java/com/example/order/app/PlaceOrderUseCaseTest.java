package com.example.order.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.order.app.dto.DiscountType;
import com.example.order.app.dto.OrderRequest;
import com.example.order.app.dto.OrderResult;
import com.example.order.domain.model.OrderPersistenceModel;
import com.example.order.domain.model.Product;
import com.example.order.infrastructure.persistence.ProductRepositoryInMemory;
import com.example.order.port.inbound.PlaceOrderUseCase;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.SaveOrderPort;

@ExtendWith(MockitoExtension.class)
public class PlaceOrderUseCaseTest {
	@Mock
	OrderService orderService;
	@Mock
	ProductRepository products;
	@Mock
	SaveOrderPort savePort;
	PlaceOrderUseCase app;

	@BeforeEach
	void setUp() {
		app = new PlaceOrderUseCase(orderService, products, savePort);
	}

	@Test
	@Tag("anchor")
	@DisplayName("ProductRepository配線テスト")
	void place_and_save_with_inmemory_products() {
		// InMemory repo に商品を仕込む
		var repo = new ProductRepositoryInMemory(Map.of(
				"P001", new Product("P001", "Apple", new BigDecimal("1000.00")),
				"P002", new Product("P002", "Banana", new BigDecimal("500.00"))));

		// 既存のモックは savePort と orderService だけ使う
		when(orderService.placeOrder(any())).thenReturn(new OrderResult(
				new BigDecimal("2500.00"),
				new BigDecimal("50.00"),
				new BigDecimal("2450.00"),
				new BigDecimal("245.00"),
				new BigDecimal("2695"),
				List.of(DiscountType.MULTI_ITEM)));
		when(savePort.save(any())).thenReturn("ORD-IM-001");

		PlaceOrderUseCase app = new PlaceOrderUseCase(orderService, repo, savePort);

		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP,
				List.of(new OrderRequest.Line("P1", 2), new OrderRequest.Line("P2", 1)));

		String id = app.execute(req);
		assertThat(id).isEqualTo("ORD-IM-001");

		// 生成された永続化モデルの中身を捕捉
		var cap = ArgumentCaptor.forClass(OrderPersistenceModel.class);
		verify(savePort).save(cap.capture());
		var m = cap.getValue();

		assertThat(m.region()).isEqualTo("JP");
		assertThat(m.netBefore()).isEqualByComparingTo("2500.00");
		assertThat(m.totalDiscount()).isEqualByComparingTo("50.00");
		assertThat(m.totalTax()).isEqualByComparingTo("245.00");
		assertThat(m.gross()).isEqualByComparingTo("2695");
		assertThat(m.appliedDiscounts()).containsExactly(DiscountType.MULTI_ITEM);

		// 行レベル：InMemoryから単価が引けているか（マッピング検証）
		assertThat(m.lines()).hasSize(2);
		assertThat(m.lines().get(0).productId()).isEqualTo("P001");
		assertThat(m.lines().get(0).unitPrice()).isEqualByComparingTo("1000.00");
		assertThat(m.lines().get(1).productId()).isEqualTo("P002");
		assertThat(m.lines().get(1).unitPrice()).isEqualByComparingTo("500.00");

		// 呼び出し順：place → save
		InOrder io = inOrder(orderService, savePort);
		io.verify(orderService).placeOrder(req);
		io.verify(savePort).save(any());
		io.verifyNoMoreInteractions();
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

		var id = app.execute(req);
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
		assertThatThrownBy(() -> app.execute(req)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(savePort);
	}

	@Test
	@Tag("anchor")
	void verify_calls_save_last_order() {
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

		app.execute(req);

		InOrder inOrder = inOrder(orderService, savePort);
		inOrder.verify(orderService).placeOrder(req);
		inOrder.verify(savePort).save(any());

	}
}
