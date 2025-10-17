package com.example.order.app;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.order.domain.model.Product;
import com.example.order.domain.policy.PercentCapPolicy;
import com.example.order.dto.DiscountType;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderRequest.Line;
import com.example.order.dto.OrderResult;
import com.example.order.port.outbound.InventoryService;
import com.example.order.port.outbound.ProductRepository;
import com.example.order.port.outbound.TaxCalculator;

@ExtendWith(MockitoExtension.class)
/**
 * 関連ADR:
 *  - ADR-001 金額スケール正規化
 *  - ADR-002 TaxCalculator に「丸め前の税額」を返す API を追加する
 *  - ADR-003 Repository の findById は null を返さない（“存在しない”は Optional.empty）
 *  - ADR-004 DiscountType enum化
 */
class OrderServiceTest {

  @Mock ProductRepository products;
  @Mock InventoryService inventory;
  @Mock TaxCalculator tax;

  OrderService sut;

  @BeforeEach
  void setUp() {
    sut = new OrderService(products, inventory, tax);
    // 全テスト共通：デフォルトは“0税率”
    lenient().when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    lenient().when(tax.addTax(any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Nested class Guards {
    @Test
    @Disabled
    @DisplayName("G-1-1: linesが nullのとき IAEがThrowされる")
    void throws_when_lines_is_null() {
    	// Given: lines = null
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, null);
    	// When: sut.placeOrder(req) Then: IAE
    	assertThrows(IllegalArgumentException.class, () -> sut.placeOrder(req));
    	verifyNoInteractions(products, inventory, tax);
    }

    @Test
    @Disabled
    @DisplayName("G-1-2: linesが 空のとき IAEがThrowされる")
    void throws_when_lines_is_empty() {
    	// Given: lines = null
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of());
    	// When: sut.placeOrder(req) Then: IAE
    	assertThatThrownBy(() -> sut.placeOrder(req))
    		.isInstanceOf(IllegalArgumentException.class)
    		.hasMessageContainingAll("lines");
    	verifyNoInteractions(products, inventory, tax);
    }

    @Test
    @Tag("anchor")
    void throwsWhenQtyNonPositive() {
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, 
    			List.of(new OrderRequest.Line("P001", 0)) // 0 でガード
        );
        assertThatThrownBy(() -> sut.placeOrder(req))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> blankStrings() {
        return Stream.of(
            null,
            "",
            " ",
            "   ",
            "\t",
            "\n"
        );
    }
    @ParameterizedTest
    @Disabled
    @MethodSource("blankStrings")
    @DisplayName("G-3-1: regionが nullまたは空または空文字の時 IAEがThrowされる")
    void throws_when_region_is_blank(String region) {
    	// Given: region = null or "" or " "etc.blank strings
    	OrderRequest req = new OrderRequest(region, RoundingMode.HALF_UP, List.of(new Line("P01", 5)));
    	// When: sut.placeOrder(req) Then: IAE
    	assertThatThrownBy(() -> sut.placeOrder(req))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContainingAll("region");
    	verifyNoInteractions(products, inventory, tax);
    }

    @Test
    @Disabled
    @DisplayName("G-4-1: products.findById呼出結果が 未取得の時 IAEがThrowされる")
    void throws_when_products_is_not_found() {
    	// Given: Product.findbyIdの返却値がOptional.empty
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", 5), new Line("B", 5)));
    	when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100"))));
    	when(products.findById("B")).thenReturn(Optional.empty()); // これで「Bが存在しない」ケース

    	// When: findById("B") Then: IAE
    	assertThatThrownBy(() -> sut.placeOrder(req))
        	.isInstanceOf(IllegalArgumentException.class)
        	.hasMessageContaining("product not found: B");
    	verifyNoInteractions(inventory, tax);
    }
  }

  @Nested class Normal {
	@Test
	@Disabled
	@DisplayName("N-1-1: 割引適用無し")
	void check_no_discount_applicate() {
		// Given: Line("A", 5)("B", 5)
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", 5), new Line("B", 5)));
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("200"))));
		
		// モック呼出(税計算)呼出だけ確認するため任意値
		when(tax.addTax(any(), anyString(), any())).thenReturn(BigDecimal.TEN);
		when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(BigDecimal.ONE);
		doNothing().when(inventory).reserve(eq("A"), eq(5));
		
		// When: sut.placeOrder(req)
		OrderResult result = sut.placeOrder(req);

		// Then: totalNetBeforeDiscount = 1500.00, totalDiscount = 0.00
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("1500.00");
		assertThat(result.totalDiscount()).isEqualByComparingTo("0.00");
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("1500.00");
		
		verify(tax).calcTaxAmount(any(), eq("JP"), eq(RoundingMode.HALF_UP));
		verify(tax).addTax(any(), eq("JP"), eq(RoundingMode.HALF_UP));
		verify(inventory).reserve("A", 5);

	}

	@Test
	@Disabled
	@DisplayName("N-1-2: VOLUME割引適用")
	void check_volume_discount_applicate() {
		// Given: Line("A", 15)("B", 5)
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", 15), new Line("B", 5)));
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("200"))));

		// When: sut.placeOrder(req)
		OrderResult result = sut.placeOrder(req);

		// Then: totalNetBeforeDiscount = 2500.00, totalDiscount = 75.00, totalNetAfterDiscount = 2425.00, appliedDiscounts = [VOLUME}
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("2500.00");
		assertThat(result.totalDiscount()).isEqualByComparingTo("75.00");
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("2425.00");
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(List.of(DiscountType.VOLUME));	
	}

	@Test
	@Disabled
	@DisplayName("N-1-3: MULTI_ITEM割引適用")
	void check_multi_item_discount_applicate() {
		// Given: Line("A", 5)("B", 5)("C", 5)("D", 5)("E", 5)
		List<Line>lines = List.of(new Line("A", 5), new Line("B", 5), new Line("C", 5), new Line("D", 5), new Line("E", 5));
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("200"))));
		when(products.findById("C")).thenReturn(Optional.of(new Product("C", new BigDecimal("300"))));
		when(products.findById("D")).thenReturn(Optional.of(new Product("D", new BigDecimal("400"))));
		when(products.findById("E")).thenReturn(Optional.of(new Product("E", new BigDecimal("500"))));

		// When: sut.placeOrder(req)
		OrderResult result = sut.placeOrder(req);

		// Then: totalNetBeforeDiscount = 7500.00, totalDiscount = 150.00, totalNetAfterDiscount = 7275.00, appliedDiscounts = [MULTI_ITEM}
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("7500.00");
		assertThat(result.totalDiscount()).isEqualByComparingTo("150.00");
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("7350.00");
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(List.of(DiscountType.MULTI_ITEM));
	}

	@Test
	@Disabled
	@DisplayName("N-1-4: HIGH_AMOUNT割引適用")
	void check_high_amount_discount_applicate() {
		List<Line>lines = List.of(new Line("A", 5), new Line("B", 5));
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);
		
		// Given: Product = ("A","10000"), ("B", "20000")
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("10000"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("20000"))));
		
		// When: sut.placeOrder(req)
		OrderResult result = sut.placeOrder(req);

		// Then: totalNetBeforeDiscount = 150000.00, totalDiscount = 4500.00, totalNetAfterDiscount = 1455000.00, appliedDiscounts = [HIGH_AMOUNT}
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("150000.00");
		assertThat(result.totalDiscount()).isEqualByComparingTo("4500.00");
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("145500.00");
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(List.of(DiscountType.HIGH_AMOUNT));
	}

	@Test
	@Disabled
	@DisplayName("N-2-1: TaxCalculator mode指定あり")
	void check_taxCalclator_mode_exist() {
		List<Line>lines = List.of(new Line("A", 5), new Line("B", 5));
		// Given: OrderRequest = any(), RoundingMode.HALF_DOWN, lines 割引なしになるよう設定
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_DOWN, lines);
		
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("1000"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("1000"))));
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
	void check_taxCalclator_mode_null() {
		List<Line>lines = List.of(new Line("A", 5), new Line("B", 5));
		// Given: OrderRequest = any(), null, lines 割引なしになるよう設定
		OrderRequest req = new OrderRequest("JP", null, lines);

		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("1000"))));
		when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("1000"))));
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
    void endToEnd_happyPath_returnsExpectedTotalsAndLabels() {
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP,List.of(new OrderRequest.Line("P001", 1)));

    	    // 最小スタブ：例外を出さないようにだけ
    	    doNothing().when(inventory).reserve(anyString(), anyInt());

    	    var result = sut.placeOrder(req);

    	    // いまは仮実装なので 0 で通す（錨は“通し線”を確保するのが目的）
    	    assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("0");
    	    assertThat(result.totalDiscount()).isEqualByComparingTo("0");
    	    assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("0");
    	    assertThat(result.totalTax()).isEqualByComparingTo("0");
    	    assertThat(result.totalGross()).isEqualByComparingTo("0");
    	    assertThat(result.appliedDiscounts()).isEmpty();
    }
  }

  @Nested class Threshold {
	static Stream<Arguments> volumeDiscountThresholds() {
		return Stream.of(
				Arguments.of(9,  new BigDecimal("9000.00"),  new BigDecimal("0.00"), new BigDecimal("9000.00"), List.of()),
				Arguments.of(10, new BigDecimal("10000.00"), new BigDecimal ("500.00"), new BigDecimal("9500.00"), List.of(DiscountType.VOLUME)),
				Arguments.of(11, new BigDecimal("11000.00"), new BigDecimal ("550.00"), new BigDecimal("10450.00"), List.of(DiscountType.VOLUME))
		);
	}
	@ParameterizedTest
	@Disabled
	@DisplayName("T-1-1: VOLUME割引適用閾値")
	@MethodSource("volumeDiscountThresholds")
    void volumeBoundary_qty9_10_11(int qty, BigDecimal expectedNet, BigDecimal expectedDiscount, BigDecimal expectedAfterDiscount, List<DiscountType> expectedLabels) {
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("1000"))));

		// Given: qty = 9/10/11
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", qty)));

		// When: sut.placeOrder(req)
		OrderResult result = sut.placeOrder(req);

		/* Then: totalNetBeforeDiscount = 9000.00/10000.00/11000.00,
		 * totalDiscount = 0.00/500.00/550.00,
		 * totalNetAfterDiscount = 9000.00/9500.00/10450.00,
		 * appliedDiscounts = []/[VOLUME]/[VOLUME]
		 */
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo(expectedNet);
		assertThat(result.totalDiscount()).isEqualByComparingTo(expectedDiscount);
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo(expectedAfterDiscount);
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(expectedLabels);
	}
	static Stream<Arguments> multiItemDiscountThresholds() {
		return Stream.of(
				Arguments.of(List.of(new Line("A", 1), new Line("B", 1)),  new BigDecimal("300.00"),  new BigDecimal("0.00"), new BigDecimal("300.00"), List.of()),
				Arguments.of(List.of(new Line("A", 1), new Line("B", 1), new Line("C", 1)), new BigDecimal("600.00"), new BigDecimal ("12.00"), new BigDecimal("588.00"), List.of(DiscountType.MULTI_ITEM)),
				Arguments.of(List.of(new Line("A", 1), new Line("B", 1), new Line("C", 1), new Line("D", 1)), new BigDecimal("1000.00"), new BigDecimal ("20.00"), new BigDecimal("980.00"), List.of(DiscountType.MULTI_ITEM))
		);
	}

	private static final Map<String, BigDecimal> PRICE = Map.of(
			  "A", new BigDecimal("100"),
			  "B", new BigDecimal("200"),
			  "C", new BigDecimal("300"),
			  "D", new BigDecimal("400")
	);

	class FakeProductRepository implements ProductRepository {
		  private final Map<String, BigDecimal> priceMap;
		  FakeProductRepository(Map<String, BigDecimal> m){ this.priceMap = m; }
		  public Optional<Product> findById(String id) {
		    var p = priceMap.get(id);
		    return p == null ? Optional.empty() : Optional.of(new Product(id, p));
		  }
	}

	@ParameterizedTest
	@Disabled
	@DisplayName("T-1-2: MULTI_ITEM割引適用閾値")
	@MethodSource("multiItemDiscountThresholds")
    void multiItemBoundary_kinds2_3_4(List<Line> lines, BigDecimal expectedNet, BigDecimal expectedDiscount, BigDecimal expectedAfterDiscount, List<DiscountType> expectedLabels) {
		FakeProductRepository fakeRepo = new FakeProductRepository(PRICE);

    	// Given: line.size() = 2/3/4
    	OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);

    	// When: sut.placeOrder(req)
    	sut = new OrderService(fakeRepo, inventory, tax);
    	OrderResult result = sut.placeOrder(req);
    	
		/* Then: totalNetBeforeDiscount = 300.00/600.00/1000.00,
		 * totalDiscount = 0.00/12.00/20.00,
		 * totalNetAfterDiscount = 300.00/588.00/980.00,
		 * appliedDiscounts = []/[MULTI_ITEM]/[MULTI_ITEM]
		 */
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo(expectedNet);
		assertThat(result.totalDiscount()).isEqualByComparingTo(expectedDiscount);
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo(expectedAfterDiscount);
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(expectedLabels);
    }

	
	static Stream<Arguments> highAmountDiscountThresholds() {
		return Stream.of(
				Arguments.of(new BigDecimal("99999.00"),List.of(new Line("A", 1)), new BigDecimal("99999.00"),  new BigDecimal("0.00"), new BigDecimal("99999.00"), List.of()),
				Arguments.of(new BigDecimal("100000.00"),List.of(new Line("A", 1)), new BigDecimal("100000.00"), new BigDecimal ("3000.00"), new BigDecimal("97000.00"), List.of(DiscountType.HIGH_AMOUNT)),
				Arguments.of(new BigDecimal("100001.00"),List.of(new Line("A", 1)), new BigDecimal("100001.00"), new BigDecimal ("3000.03"), new BigDecimal("97000.97"), List.of(DiscountType.HIGH_AMOUNT))
		);
	}

	@ParameterizedTest
	@Disabled
	@DisplayName("T-1-3: HIGH_AMOUNT割引適用閾値")
	@MethodSource("highAmountDiscountThresholds")
    void highAmountBoundary_99999_100000_100001(BigDecimal price, List<Line> lines, BigDecimal expectedNet, BigDecimal expectedDiscount, BigDecimal expectedAfterDiscount, List<DiscountType> expectedLabels) {
		OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);
		// Given: Product.price = 99999/100000/100001
		when(products.findById("A")).thenReturn(Optional.of(new Product("A", price)));

		// When: sut.placeOrder(req)
    	OrderResult result = sut.placeOrder(req);

    	/* Then: totalNetBeforeDiscount = 99999.00/100000.00/100001.00,
		 * totalDiscount = 0.00/3000.00/3000.00,
		 * totalNetAfterDiscount = 99999.00/97000.00/97001.00,
		 * appliedDiscounts = []/[HIGH_AMOUNT]/[HIGH_AMOUNT]
		 */
		assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo(expectedNet);
		assertThat(result.totalDiscount()).isEqualByComparingTo(expectedDiscount);
		assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo(expectedAfterDiscount);
		assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(expectedLabels);
	}

	@Test
	@Disabled
	@DisplayName("T-2-1: スケールと正規化確認 ADR-001")
	void normalize_scale_of_gross_and_tax() {
		// Given
        when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100000"))));
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

  @Nested class VerifyCalls {
	// 使われたIDだけ返すAnswer（price表）
	private void stubProductsPriceTable(Map<String, String> table) {
		when(products.findById(anyString())).thenAnswer(inv -> {String id = inv.getArgument(0, String.class);
		String p = table.get(id);
		return p == null ? Optional.empty() : Optional.of(new Product(id, new BigDecimal(p)));
	    });
	  }
    @Test
    @Disabled
    @DisplayName("V-1-1: 正常系の呼び出し順・回数・引数一致")
    void order_calls_dependencies_in_strict_order_and_passes_region_mode() {
    	// Given: product = (["A", "100"], ["B", "200"], ["C", "300"])
        stubProductsPriceTable(Map.of(
            "A", "100",
            "B", "200",
            "C", "300"
        ));
        List<Line> lines = List.of(
            new OrderRequest.Line("A", 1),
            new OrderRequest.Line("B", 2),
            new OrderRequest.Line("C", 3)
        );
        OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_DOWN, lines);

        // When: sut.placeOrder(req)
        sut.placeOrder(req);

        // Then: inOrder(products, inventory, tax)
        InOrder inOrder = inOrder(products, inventory, tax);

        // findById×n（順序はlinesと同じ）
        inOrder.verify(products).findById("A");
        inOrder.verify(products).findById("B");
        inOrder.verify(products).findById("C");

        // reserve×n（同順序・同数量）
        inOrder.verify(inventory).reserve("A", 1);
        inOrder.verify(inventory).reserve("B", 2);
        inOrder.verify(inventory).reserve("C", 3);

        // 税：calc → add の順、同じ引数が渡る
        ArgumentCaptor<BigDecimal> netCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> regionCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RoundingMode> modeCap = ArgumentCaptor.forClass(RoundingMode.class);

        inOrder.verify(tax).calcTaxAmount(netCap.capture(), regionCap.capture(), modeCap.capture());
        inOrder.verify(tax).addTax(eq(netCap.getValue()), eq(regionCap.getValue()), eq(modeCap.getValue()));

        assertThat(regionCap.getValue()).isEqualTo("JP");
        assertThat(modeCap.getValue()).isEqualTo(RoundingMode.HALF_DOWN);

        // 余計な呼び出しが無いこと
        inOrder.verifyNoMoreInteractions();
        verifyNoMoreInteractions(products, inventory, tax);
    }

    @Test
    @Disabled
    @DisplayName("V-1-2: mode が null のとき HALF_UP が渡る（デフォルト）")
    void order_passes_HALF_UP_when_mode_is_null() {
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

    @Test
    @Disabled
    @DisplayName("V-1-3: 在庫例外で税が呼ばれない（異常の順序保証）")
    void when_inventory_throws_tax_is_never_called() {
    	// Given: Product = (["A", 100], ["B", 200])
        stubProductsPriceTable(Map.of(
            "A", "100",
            "B", "200"
        ));

        // 2本目のreserveで例外
        doNothing().when(inventory).reserve("A", 1);
        doThrow(new RuntimeException("inventory down")).when(inventory).reserve("B", 2);

        OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new OrderRequest.Line("A", 1), new OrderRequest.Line("B", 2)));
        
        // When: sut.placeOrder(req) / Then: RuntimeException
        assertThatThrownBy(() -> sut.placeOrder(req))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("inventory");

        // findByIdは2件呼ばれている（順序検証）
        InOrder inOrder = inOrder(products, inventory, tax);
        inOrder.verify(products).findById("A");
        inOrder.verify(products).findById("B");
        inOrder.verify(inventory).reserve("A", 1);
        inOrder.verify(inventory).reserve("B", 2);

        // 税計算は呼ばれない
        verify(tax, never()).calcTaxAmount(any(), anyString(), any());
        verify(tax, never()).addTax(any(), anyString(), any());
    }
  }

  @Nested class Abnormal {
	  @Test
	  @Tag("anchor")
	  void inventoryThrows_taxNotCalled() {
		  OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new OrderRequest.Line("P001", 1))
	    );
	    // 在庫側が落ちる想定
	    doThrow(new RuntimeException("no stock")).when(inventory).reserve(anyString(), anyInt());

	    assertThatThrownBy(() -> sut.placeOrder(req))
	        .isInstanceOf(RuntimeException.class);

	    // 税は呼ばれないこと（メソッド名が曖昧なら相互作用ゼロで検証）
	    verifyNoInteractions(tax);
	  }
  }
 
  @Nested class FormatAndADR {
	  @Test
	  @Disabled("30% cap is a future guard Refs: ADR-004")
	  @DisplayName("合計割引が素合計の30%を超える場合、Cap=30%で丸められる")
	  void clampsTotalDiscountAtThirtyPercentOfSubtotal_whenRawDiscountExceedsCap() {
		  // Given: Capを越える“仮定の世界”を記述（現実には到達しない）
		  // 例: 小計をSとし、仮に原始割引が0.45Sになったとすると、結果は0.30Sで固定されるべき。
		  var lines = List.of(
				  new Line("A", 10), // VOLUMEを起動しやすい条件
				  new Line("B", 10),
				  new Line("C", 10)
				  );
		  when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("10000"))));
		  when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("20000"))));
		  when(products.findById("C")).thenReturn(Optional.of(new Product("C", new BigDecimal("30000"))));
		  // 税はこのテストの主題外：呼出確認のみで値検証はしない
		  when(tax.addTax(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);
		  when(tax.calcTaxAmount(any(), anyString(), any())).thenReturn(BigDecimal.ZERO);

		  var req = new OrderRequest("JP", RoundingMode.HALF_UP, lines);

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

	  @Test
	  @Disabled
	  @DisplayName("割引ポリシー確認テスト")
	  void checkCapPolicy() {
			// Given: Line("A", 15)("B", 5)
			OrderRequest req = new OrderRequest("JP", RoundingMode.HALF_UP, List.of(new Line("A", 15), new Line("B", 5)));
			when(products.findById("A")).thenReturn(Optional.of(new Product("A", new BigDecimal("100"))));
			when(products.findById("B")).thenReturn(Optional.of(new Product("B", new BigDecimal("200"))));

			
			// Given: PersentPolicy = 0.02
			OrderService sut = new OrderService(products, inventory, tax, new PercentCapPolicy(new BigDecimal("0.02")));
			
			// When: sut.placeOrder(req)
			OrderResult result = sut.placeOrder(req);

			// Then: totalNetBeforeDiscount = 2500.00, totalDiscount = 50.00, totalNetAfterDiscount = 2450.00, appliedDiscounts = [VOLUME}
			assertThat(result.totalNetBeforeDiscount()).isEqualByComparingTo("2500.00");
			assertThat(result.totalDiscount()).isEqualByComparingTo("50.00");
			assertThat(result.totalNetAfterDiscount()).isEqualByComparingTo("2450.00");
			assertThat(result.appliedDiscounts()).containsExactlyInAnyOrderElementsOf(List.of(DiscountType.VOLUME));	

	  }
  }
}