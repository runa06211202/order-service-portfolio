package com.example.order.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.order.domain.model.Product;
import com.example.order.dto.DiscountType;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderRequest.Line;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;
import com.example.order.step.CapPolicy;
import com.example.order.step.HighAmountDiscount;
import com.example.order.step.MultiItemDiscount;
import com.example.order.step.VolumeDiscount;

@ExtendWith(MockitoExtension.class)
/**
 * 関連ADR:
 *  - ADR-001 金額スケール正規化
 *  - ADR-002 TaxCalculator に「丸め前の税額」を返す API を追加する
 *  - ADR-003 Repository の findById は null を返さない（“存在しない”は Optional.empty）
 *  - ADR-004 DiscountType enum化
 *  - ADR-006: 副作用を後段に寄せた呼び出し順序の再定義（calculate → reserve）
 *  - ADR-007: 在庫可用性チェック導入と呼び出し順序の再定義（availability→calculate→reserve）
 */
class OrderServiceTest {

	@Mock
	ProductRepository products;
	@Mock
	InventoryService inventory;
	@Mock
	TaxCalculator tax;

	OrderService sut;

	@BeforeEach
	void setUp() {
		sut = new OrderService(products, inventory, tax);
		// 全テスト共通：デフォルトは“0税率”
		lenient().when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);
		lenient().when(tax.addTax(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Nested
	class OrderServiceGuardTest {
		@Test
		@DisplayName("req が nullのとき IAEがThrowされる")
		void placeOrder_throws_when_orderRequest_null() {
			// Given: lines = null
			OrderRequest req = null;
			// When: sut.placeOrder(req) Then: IAE "lines must not be null"
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("orderRequest must not be null");
			// Guardが発動した場合は副作用を起こさない（ドメインの安全保証）
			verifyNoInteractions(products, inventory, tax);
		}

		@Test
		@DisplayName("linesが nullのとき IAEがThrowされる")
		void placeOrder_throws_when_lines_null() {
			// Given: lines = null
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, null);
			// When: sut.placeOrder(req) Then: IAE "lines must not be null"
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("lines must not be null");
			// Guardが発動した場合は副作用を起こさない（ドメインの安全保証）
			verifyNoInteractions(products, inventory, tax);
		}

		@Test
		@DisplayName("linesが 空のとき IAEがThrowされる")
		void placeOrder_throws_when_lines_empty() {
			// Given: lines = null
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of());
			// When: sut.placeOrder(req) Then: IAE "lines must not be empty"
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("lines must not be empty");
			// Guardが発動した場合は副作用を起こさない（ドメインの安全保証）
			verifyNoInteractions(products, inventory, tax);
		}

		@Test
		@Tag("anchor")
		@DisplayName("Normal系錨テスト (処理フロー通し確認）")
		void placeOrder_throws_when_qty_nonPositive() {
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP,
					List.of(new OrderRequest.Line("P001", 0)) // 0 でガード
			);
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class);
			// Guardが発動した場合は副作用を起こさない（ドメインの安全保証）
			verifyNoInteractions(products, inventory, tax);
		}

		static Stream<String> blankStrings() {
			return Stream.of(
					null,
					"",
					" ",
					"   ",
					"\t",
					"\n");
		}

		@ParameterizedTest
		@Disabled
		@MethodSource("blankStrings")
		@DisplayName("G-3-1: regionが nullまたは空または空文字の時 IAEがThrowされる")
		void placeOrder_throws_when_region_is_blank(String region) {
			// Given: region = null or "" or " "etc.blank strings
			OrderRequest req = new OrderRequest(region, RoundingMode.HALF_UP, List.of(new Line("P01", 5)));
			// When: sut.placeOrder(req) Then: IAE
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContainingAll("region");
			verifyNoInteractions(products, inventory);
		}

		@Test
		@Disabled
		@DisplayName("G-4-1: products.findById呼出結果が 未取得の時 IAEがThrowされる")
		void placeOrder_throws_when_products_is_not_found() {
			// Given: Product.findbyIdの返却値がOptional.empty
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP,
					List.of(new Line("A", 5), new Line("B", 5)));
			when(products.findById("A")).thenReturn(Optional.of(new Product("A", null, new BigDecimal("100"))));
			when(products.findById("B")).thenReturn(Optional.empty()); // これで「Bが存在しない」ケース

			// When: findById("B") Then: IAE
			assertThatThrownBy(() -> sut.placeOrder(req))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("product not found: B");
			verifyNoInteractions(inventory);
		}
	}

	@Nested
	class OrderServiceNormalTest {

		@Test
		@Disabled
		@DisplayName("N-2-1: TaxCalculator mode指定あり")
		void placeOrder_calc_whenTaxCalclatorModeExist() {
			List<Line> lines = List.of(new Line("A", 5), new Line("B", 5));
			// Given: OrderRequest = any(), RoundingMode.HALF_DOWN, lines 割引なしになるよう設定
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_DOWN, lines);

			when(products.findById("A")).thenReturn(Optional.of(new Product("A", null, new BigDecimal("1000"))));
			when(products.findById("B")).thenReturn(Optional.of(new Product("B", null, new BigDecimal("1000"))));
			when(tax.calcTaxAmount(any(), any(), any())).thenReturn(new BigDecimal("1000.00"));
			when(tax.addTax(any(), any(), any())).thenReturn(new BigDecimal("11000"));

			// When: sut.placeOrder(req)
			OrderResult result = sut.placeOrder(req);

			// Then: totalTax = 1000.00 totalGross = 11000 calcTaxAmount(10000.00,any(),RoundingMode.HALF_DOWN), addTax(10000.00,any(),RoundingMode.HALF_DOWN)
			assertThat(result.totalTax()).isEqualByComparingTo("1000.00");
			assertThat(result.totalGross()).isEqualByComparingTo("11000");
			ArgumentCaptor<RoundingMode> modeCaptor = ArgumentCaptor.forClass(RoundingMode.class);

			// calcTaxAmount, addTax呼び出しの引数をキャプチャ
			verify(tax).calcTaxAmount(any(), any(), modeCaptor.capture());
			verify(tax).addTax(any(), any(), modeCaptor.capture());

			// 確認：HALF_DOWN が渡っていること
			assertThat(modeCaptor.getValue()).isEqualTo(RoundingMode.HALF_DOWN);
		}

		@Test
		@Disabled
		@DisplayName("N-2-2: TaxCalculator mode指定なし(null)")
		void placeOrder_calc_whenTaxCalclatorModeNull() {
			List<Line> lines = List.of(new Line("A", 5), new Line("B", 5));
			// Given: OrderRequest = any(), null, lines 割引なしになるよう設定
			OrderRequest req = new OrderRequest("JP", null, lines);

			when(products.findById("A")).thenReturn(Optional.of(new Product("A", null, new BigDecimal("1000"))));
			when(products.findById("B")).thenReturn(Optional.of(new Product("B", null, new BigDecimal("1000"))));
			when(tax.calcTaxAmount(any(), any(), any())).thenReturn(new BigDecimal("1000.00"));
			when(tax.addTax(any(), any(), any())).thenReturn(new BigDecimal("11000"));

			// When: sut.placeOrder(req)
			OrderResult result = sut.placeOrder(req);

			// Then: totalTax = 1000.00 totalGross = 11000 calcTaxAmount(10000.00,any(),RoundingMode.HALF_UP), addTax(10000.00,any(),RoundingMode.HALF_UP)
			assertThat(result.totalTax()).isEqualByComparingTo("1000.00");
			assertThat(result.totalGross()).isEqualByComparingTo("11000");
			ArgumentCaptor<RoundingMode> modeCaptor = ArgumentCaptor.forClass(RoundingMode.class);

			// calcTaxAmount, addTax呼び出しの引数をキャプチャ
			verify(tax).calcTaxAmount(any(), any(), modeCaptor.capture());
			verify(tax).addTax(any(), any(), modeCaptor.capture());

			// 確認：HALF_DOWN が渡っていること
			assertThat(modeCaptor.getValue()).isEqualTo(RoundingMode.HALF_UP);
		}

		@Test
		@Tag("anchor")
		@DisplayName("placeOrder通しhappyパス確認")
		void endToEnd_happyPath_returnsExpectedTotalsAndLabels() {
			// Given
			var pid1 = "P001";
			var pid2 = "P002";
			var line1 = new OrderRequest.Line(pid1, 2);
			var line2 = new OrderRequest.Line(pid2, 1);
			OrderRequest req = new OrderRequest("JP", null, List.of(line1, line2));
			// 可用性チェック追加
			when(inventory.checkAvailable(pid1, 2)).thenReturn(true);
			when(inventory.checkAvailable(pid2, 1)).thenReturn(true);
			when(products.findById(pid1))
					.thenReturn(Optional.of(new Product(pid1, "Apple", new BigDecimal("100"))));
			when(products.findById(pid2))
					.thenReturn(Optional.of(new Product(pid2, "Banana", new BigDecimal("200"))));
			doNothing().when(inventory).reserve(anyString(), anyInt());
			when(tax.calculate(any(BigDecimal.class), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 仮の税率10%

			// When
			var result = sut.placeOrder(req);

			// Then
			assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("400"); // 100*2 + 200
			assertThat(result.totalDiscount()).isEqualByComparingTo("0");
			assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("400");
			assertThat(result.totalTax()).isEqualByComparingTo("40"); // 400 * 0.1
			assertThat(result.totalGross()).isEqualByComparingTo("440");
			assertThat(result.appliedDiscounts()).isEmpty();
		}
	}

	@Nested
	class Threshold {
		@Test
		@Disabled
		@DisplayName("T-2-1: スケールと正規化確認 ADR-001")
		void placeOrder_calc_whenNormalizeScaleOfGrossAndTax() {
			// Given
			when(products.findById("A")).thenReturn(Optional.of(new Product("A", null, new BigDecimal("100000"))));
			when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(new BigDecimal("10000.4999")); // scale=4
			when(tax.addTax(any(), anyString(), any())).thenReturn(new BigDecimal("110000.49")); // scale=2

			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", 1)));

			// When
			OrderResult result = sut.placeOrder(req);

			// Then: スケールと金額の確認
			assertThat(result.totalGross().scale()).isEqualTo(0);
			assertThat(result.totalGross()).isEqualByComparingTo("110000");
			assertThat(result.totalTax().scale()).isEqualTo(2);
			assertThat(result.totalTax()).isEqualByComparingTo("10000.50");
		}
	}

	@Nested
	class OrderServiceFlowTest {
		@Test
		@DisplayName("在庫確保が最後に注文列毎に呼ばれていること")
		void placeOrder_flow_whenReservesInOrderAfterDiscountsOnlyOnceEach() {
			// Given
			var pid1 = "P001";
			var pid2 = "P002";
			var line1 = new OrderRequest.Line(pid1, 2);
			var line2 = new OrderRequest.Line(pid2, 3);
			OrderRequest req = new OrderRequest("JP", null, List.of(line1, line2));
			// 可用性チェック追加
			when(inventory.checkAvailable(pid1, 2)).thenReturn(true);
			when(inventory.checkAvailable(pid2, 3)).thenReturn(true);
			// findByIdの例外処理を追加したためProductRepositoryのモック設定追加（例外防止）
			when(products.findById(pid1))
					.thenReturn(Optional.of(new Product(pid1, "Apple", new BigDecimal("100"))));
			when(products.findById(pid2))
					.thenReturn(Optional.of(new Product(pid2, "Banana", new BigDecimal("200"))));

			// 税計算はどんな入力でも0返すようにしておく
			when(tax.calculate(any(), any())).thenReturn(BigDecimal.ZERO);

			// When
			sut.placeOrder(req);

			// Then
			InOrder inOrder = inOrder(inventory);
			inOrder.verify(inventory).reserve(pid1, 2);
			inOrder.verify(inventory).reserve(pid2, 3);
			inOrder.verifyNoMoreInteractions();
		}

		@Test
		@DisplayName("在庫確保の前に税計算が呼ばれていること")
		void placeOrder_flow_whenCalculateBeforeReserveOrderIsFixed() {
			// Given
			var pid1 = "P001";
			var pid2 = "P002";
			var l1 = new OrderRequest.Line(pid1, 2);
			var l2 = new OrderRequest.Line(pid2, 1);
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1, l2));

			// 可用性チェック追加
			when(inventory.checkAvailable(pid1, 2)).thenReturn(true);
			when(inventory.checkAvailable(pid2, 1)).thenReturn(true);
			when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "Apple", new BigDecimal("100"))));
			when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "Banana", new BigDecimal("200"))));
			// 税は任意の0.1など（丸め仕様に合わせる）
			when(tax.calculate(any(BigDecimal.class), eq("JP"))).thenReturn(new BigDecimal("0.10"));
			doNothing().when(inventory).reserve(anyString(), anyInt());

			InOrder order = inOrder(tax, inventory);

			// When
			sut.placeOrder(req);

			// Then
			// 計算（税）→ 在庫 の順序
			order.verify(tax).calculate(any(BigDecimal.class), eq("JP"));
			order.verify(inventory).reserve(pid1, 2);
			order.verify(inventory).reserve(pid2, 1);
			order.verifyNoMoreInteractions();
		}

		@Test
		@DisplayName("可用性チェック→税計算→在庫確保の順に呼ばれていること")
		void placeOrder_flow_whenCheckAvailableIsCalledBeforeCalculationsAndReserve() {
			//Given 
			var pid1 = "P001";
			var pid2 = "P002";
			var l1 = new OrderRequest.Line(pid1, 2);
			var l2 = new OrderRequest.Line(pid2, 1);
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1, l2));
			when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "Apple", new BigDecimal("100"))));
			when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "Banana", new BigDecimal("200"))));
			when(tax.calculate(any(), any())).thenReturn(BigDecimal.ZERO);
			when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);

			InOrder order = inOrder(inventory, tax);
			// When
			sut.placeOrder(req);

			// Then 
			// 可用性チェックが最初
			order.verify(inventory, times(2)).checkAvailable(anyString(), anyInt());
			// その後に計算（税）
			order.verify(tax).calculate(any(), any());
			// 最後に確保
			order.verify(inventory, times(2)).reserve(anyString(), anyInt());
		}

		// 使われたIDだけ返すAnswer（price表）
		private void stubProductsPriceTable(Map<String, String> table) {
			when(products.findById(anyString())).thenAnswer(inv -> {
				String id = inv.getArgument(0, String.class);
				String p = table.get(id);
				return p == null ? Optional.empty() : Optional.of(new Product(id, null, new BigDecimal(p)));
			});
		}

		@Test
		@Disabled
		@DisplayName("V-1-2: mode が null のとき HALF_UP が渡る（デフォルト）")
		void placeOrder_flow_whenOrderPassesHALFUPWhenModeIsNull() {
			// Given: Product = (["A", "1000"])
			stubProductsPriceTable(Map.of("A", "1000"));
			OrderRequest req = new OrderRequest("JP", null, List.of(new OrderRequest.Line("A", 1)));

			// When: sut.placeOrder(req)
			sut.placeOrder(req);

			// Then: modeCap = RoundingMode.HALF_UP
			ArgumentCaptor<RoundingMode> modeCap = ArgumentCaptor.forClass(RoundingMode.class);
			verify(tax).calcTaxAmount(any(), anyString(), modeCap.capture());
			verify(tax).addTax(any(), anyString(), eq(modeCap.getValue()));
			assertThat(modeCap.getValue()).isEqualTo(RoundingMode.HALF_UP);
		}

		@Nested
		class OrderServiceAbnormalTest {
			@Test
			@Tag("anchor")
			@DisplayName("checkAvailable が false のとき、早期リターンを確認")
			void placeOrder_throws_whenInventoryThrowsPropagatesNoSave() {
				// Given
				var pid = "P001";
				var l1 = new OrderRequest.Line(pid, 2);

				// When
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1));
				when(inventory.checkAvailable(pid, 1)).thenReturn(false); // 可用性NG

				assertThatThrownBy(() -> sut.placeOrder(req)).isInstanceOf(RuntimeException.class);

				// Then: 可用性NGなら計算も確保もしない
				verifyNoInteractions(tax);
				verify(inventory, never()).reserve(anyString(), anyInt());
			}

			@Test
			@Tag("anchor")
			@DisplayName("checkAvailable が false のとき、例外伝播＆reserve未呼び出し")
			void placeOrder_throws_whenAvailabilityFalseThrowsNoReserveNoSave() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var l1 = new OrderRequest.Line(pid1, 2);
				var l2 = new OrderRequest.Line(pid2, 1);

				// When
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1, l2));

				// ここはまだPortにcheckAvailableが無いので、この時点ではコンパイルをRedにしてOK
				when(inventory.checkAvailable(pid1, 1)).thenReturn(true);
				when(inventory.checkAvailable(pid2, 1)).thenReturn(false);

				// Then
				assertThatThrownBy(() -> sut.placeOrder(req)).isInstanceOf(RuntimeException.class);

				// 可用性NGなら reserve は一切呼ばれない
				verify(inventory, never()).reserve(anyString(), anyInt());
			}

			@Test
			@DisplayName("最終確保（reserve）で例外が出る")
			void placeOrder_throws_whenReserveThrowsPropagatesAfterCalc() {
				// Given
				var pid = "P001";
				var l1 = new OrderRequest.Line(pid, 2);

				//Then 
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1));
				when(inventory.checkAvailable(pid, 1)).thenReturn(true); // ここは通す
				when(products.findById(pid))
						.thenReturn(Optional.of(new Product(pid, "Apple", new BigDecimal("100"))));
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 計算は走る
				doThrow(new RuntimeException("no stock")).when(inventory).reserve(pid, 1);

				// Then
				assertThatThrownBy(() -> sut.placeOrder(req)).isInstanceOf(RuntimeException.class);
				// 税は呼ばれてOK（計算→reserve→例外の順）
			}
		}

		@Nested
		class FormatAndADR {
			@Test
			@Disabled("30% cap is a future guard Refs: ADR-004")
			@DisplayName("合計割引が素合計の30%を超える場合、Cap=30%で丸められる")
			void placeOrder_caps_whenRawDiscountExceedsCap() {
				// Given: Capを越える“仮定の世界”を記述（現実には到達しない）
				// 例: 小計をSとし、仮に原始割引が0.45Sになったとすると、結果は0.30Sで固定されるべき。
				var lines = List.of(
						new Line("A", 10), // VOLUMEを起動しやすい条件
						new Line("B", 10),
						new Line("C", 10));
				when(products.findById("A")).thenReturn(Optional.of(new Product("A", null, new BigDecimal("10000"))));
				when(products.findById("B")).thenReturn(Optional.of(new Product("B", null, new BigDecimal("20000"))));
				when(products.findById("C")).thenReturn(Optional.of(new Product("C", null, new BigDecimal("30000"))));
				// 税はこのテストの主題外：呼出確認のみで値検証はしない
				when(tax.addTax(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);
				when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);

				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);

				// When
				var result = sut.placeOrder(req);

				// Then: 期待だけを“仕様として”残す（今は到達不能のため Disabled）
				BigDecimal subtotal = new BigDecimal("600000.00"); // 10000*10 + 20000*10 + 30000*10
				BigDecimal cap = subtotal.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);

				// 現行実装では totalDiscount ≒ 0.10S しかならないが、
				// 仕様としては “原始割引がCap超えなら totalDiscount = 0.30S に丸める”ことを宣言する。
				assertThat(result.totalDiscount()).isEqualByComparingTo(cap);
				// ラベルは Cap を追加しない（適用済み割引の記録のみ）
				// assertThat(result.appliedDiscounts()).doesNotContain(DiscountType.valueOf("CAP"));
			}
		}

		@Nested
		class DiscountRules {
			@Test
			@Tag("anchor")
			@DisplayName("VOLUME割引anchorテスト")
			void appliesVolumeDiscount_whenQtyAtLeast10() {
				//Given 
				var pid1 = "P001";
				var pid2 = "P002";
				var qty1 = 10;
				var qty2 = 5;
				var l1 = new OrderRequest.Line(pid1, qty1);
				var l2 = new OrderRequest.Line(pid2, qty2);
				var rate = new BigDecimal("0.10");
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(l1, l2));
				when(inventory.checkAvailable(pid1, qty1)).thenReturn(true);
				when(inventory.checkAvailable(pid2, qty2)).thenReturn(true);
				when(products.findById(pid1))
						.thenReturn(Optional.of(new Product(pid1, "Apple", new BigDecimal("100"))));
				when(products.findById(pid2))
						.thenReturn(Optional.of(new Product(pid2, "Banana", new BigDecimal("200"))));
				when(tax.calculate(any(), eq("JP"))).thenReturn(rate);

				// When
				OrderResult result = sut.placeOrder(req);

				// Then 各行：
				// P001: 100×10 = 1000 → 5%OFF → 950
				// P002: 200×5 = 1000
				// 小計(割引後)=1950, 割引前=2000
				assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("2000");
				assertThat(result.totalDiscount()).isEqualByComparingTo("50");
				assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("1950");// ADR-008
			}

			@Test
			@Tag("anchor")
			@DisplayName("MULTI_ITEM割引anchorテスト")
			void applies_multiItemDiscount_after_volume_in_order() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				var qty1 = 10;
				var qty2 = 1;
				var qty3 = 1;

				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, qty1),
						new OrderRequest.Line(pid2, qty2),
						new OrderRequest.Line(pid3, qty3)));

				when(inventory.checkAvailable(pid1, qty1)).thenReturn(true);
				when(inventory.checkAvailable(pid2, qty2)).thenReturn(true);
				when(inventory.checkAvailable(pid3, qty3)).thenReturn(true);
				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("100")))); // 100*10=1000
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("200")))); // 200*1=200
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("300")))); // 300*1=300
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				/* 期待計算：
				 * subtotal = 1000 + 200 + 300 = 1500
				 * volume: P1 1000 * 0.05 = 50
				 * multi_item: 1450 * 0.02 = 29 → totalDiscount = 50 + 29 = 79
				 * netAfter = 1500 - 79 = 1421
				 */
				// When
				OrderResult r = sut.placeOrder(req);

				// Then
				assertThat(r.totalNetBeforeDiscount()).isEqualByComparingTo("1500");
				assertThat(r.totalDiscount()).isEqualByComparingTo("79");
				assertThat(r.totalNetAfterDiscount()).isEqualByComparingTo("1421");
			}

			@Test
			@Tag("anchor")
			void applies_highAmountDiscount_after_volume_and_multiitem() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10), // 量割対象
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));

				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000")))); // 10000*10=100000
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000")))); // 10000
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000")))); // 5000
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				/*
				 * 計算期待:
				 * subtotal = 115000
				 * volume: P1 100000 * 0.05 = 5000 → base1 = 110000
				 * multi_item : base1 110000 * 0.02 = 2200 → base2 = 107800
				 * high_amount  : base2 107800 * 0.03 = 3234 → totalDiscount = 5000+2200+3234 = 10434
				 * netAfter = 115000 - 10434 = 104566
				 */
				OrderResult r = sut.placeOrder(req);

				assertThat(r.totalNetBeforeDiscount()).isEqualByComparingTo("115000");
				assertThat(r.totalDiscount()).isEqualByComparingTo("10434");
				assertThat(r.totalNetAfterDiscount()).isEqualByComparingTo("104566");
			}

			@Test
			@DisplayName("cap未発動確認テスト(30％固定)")
			void cap_limits_total_discount_to_30_percent_of_subtotal() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10), // 量割対象
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));

				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000")))); // 10000*10=100000
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000")))); // 10000
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000")))); // 5000
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				/*
				 * 期待値（Cap未発動のはず）
				 * subtotal = 115000
				 * volume  = 100000 * 0.05 = 5000
				 * multi   = base1 110000 * 0.02 = 2200
				 * high    = base2 107800 * 0.03 = 3234
				 * sum     = 5000 + 2200 + 3234 = 10434  （30% cap は 34500、未到達）
				 */
				// When
				OrderResult r = sut.placeOrder(req);

				// Then
				assertThat(r.totalNetBeforeDiscount()).isEqualByComparingTo("115000");
				assertThat(r.totalDiscount()).isEqualByComparingTo("10434"); // Cap未発動
				assertThat(r.totalNetAfterDiscount()).isEqualByComparingTo("104566");
				// 税・総額は別サイクルで検証
			}

			@Test
			@DisplayName("cap確認テスト(capを意図的に下げて動作を検証)")
			void cap_is_configurable_when_policy_injected() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10), // 量割対象
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));
				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000")))); // 10000*10=100000
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000")))); // 10000
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000")))); // 5000
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				// ポリシー列を明示注入（既定の 30% ではなく 5% にする）
				var customPolicies = List.of(
						new VolumeDiscount(), // 1. VOLUME
						new MultiItemDiscount(), // 2. MULTI_ITEM
						new HighAmountDiscount(), // 3. HIGH_AMOUNT
						new CapPolicy(new BigDecimal("0.05")) // 4. CAP (5%)
				);
				OrderService sutWithCap5 = new OrderService(products, inventory, tax, customPolicies);

				/*
				 * 期待値（素の合算割引 10434 を 5% cap で締める）
				 * subtotal = 115000
				 * cap(5%)  = 115000 * 0.05 = 5750
				 */
				// When
				OrderResult r = sutWithCap5.placeOrder(req);

				// Then
				assertThat(r.totalNetBeforeDiscount()).isEqualByComparingTo("115000");
				assertThat(r.totalDiscount()).isEqualByComparingTo("5750"); // Cap発動で 5% に丸め込まれる
				assertThat(r.totalNetAfterDiscount()).isEqualByComparingTo("109250");
			}
		}

		@Nested
		class DiscountLabels {
			@Test
			@Tag("anchor")
			@DisplayName("割引ラベル確認anchorテスト")
			void appliedDiscounts_reflects_all_triggered_discounts_in_order() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10),
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));
				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000"))));
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000"))));
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000"))));
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				// When
				OrderResult r = sut.placeOrder(req);

				// ラベル順は Volume → MultiItem → HighAmount → Cap
				assertThat(r.appliedDiscounts())
						.containsExactly(
								DiscountType.VOLUME,
								DiscountType.MULTI_ITEM,
								DiscountType.HIGH_AMOUNT);

				// Then: 金額は変わらない
				assertThat(r.totalDiscount()).isEqualByComparingTo("10434");
			}

			@Test
			@DisplayName("ADR-010追加テスト Cap到達なし")
			void cap_label_absent_when_not_engaged() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				// 30% cap に到達しない入力（これまで使ってた 115000 のケースでOK）
				var req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10),
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));
				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000"))));
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000"))));
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000"))));
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				// When
				OrderResult r = sut.placeOrder(req);

				// Then
				assertThat(r.appliedDiscounts())
						.containsExactly(DiscountType.VOLUME, DiscountType.MULTI_ITEM, DiscountType.HIGH_AMOUNT);
			}

			@Test
			@DisplayName("ADR-010追加テスト Capポリシー注入によるCap適用テスト")
			void cap_label_present_when_engaged_with_custom_cap() {
				// Given
				var pid1 = "P001";
				var pid2 = "P002";
				var pid3 = "P003";
				// Cap を 5% に下げて“確実に発動”させる
				var policies = List.of(
						new VolumeDiscount(),
						new MultiItemDiscount(),
						new HighAmountDiscount(),
						new CapPolicy(new BigDecimal("0.05")));
				var sutWithCap5 = new OrderService(products, inventory, tax, policies);

				var req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line(pid1, 10),
						new OrderRequest.Line(pid2, 1),
						new OrderRequest.Line(pid3, 1)));
				when(products.findById(pid1)).thenReturn(Optional.of(new Product(pid1, "A", new BigDecimal("10000"))));
				when(products.findById(pid2)).thenReturn(Optional.of(new Product(pid2, "B", new BigDecimal("10000"))));
				when(products.findById(pid3)).thenReturn(Optional.of(new Product(pid3, "C", new BigDecimal("5000"))));
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10")); // 10%

				// When
				OrderResult r = sutWithCap5.placeOrder(req);

				// Then
				assertThat(r.appliedDiscounts())
						.containsExactly(DiscountType.VOLUME, DiscountType.MULTI_ITEM, DiscountType.HIGH_AMOUNT,
								DiscountType.CAP);
				// 金額系の期待（総割引=5% cap）は金額テスト側に任せ、ここはラベルのみ見る
			}
		}

		class TaxCalculation {
			@Test
			@Tag("anchor")
			void calculates_tax_on_discounted_total_with_rounding() {
				//Given 
				OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(
						new OrderRequest.Line("P001", 10)));
				when(products.findById("P001"))
						.thenReturn(Optional.of(new Product("P001", "A", new BigDecimal("10000"))));
				when(inventory.checkAvailable(anyString(), anyInt())).thenReturn(true);
				doNothing().when(inventory).reserve(anyString(), anyInt());
				when(tax.calculate(any(), eq("JP"))).thenReturn(new BigDecimal("0.10"));
				
				//When
				OrderResult r = sut.placeOrder(req);
				
				//Then 
				assertThat(r.totalTax()).isEqualByComparingTo("9500"); // 割引後95,000×10%
		        assertThat(r.totalGross()).isEqualByComparingTo("104500");
			}
		}
	}
}