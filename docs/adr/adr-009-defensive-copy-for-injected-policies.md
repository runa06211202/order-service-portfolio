# ADR-009: 割引ポリシー注入時の防御コピー（List.copyOf）導入
- Status: Accepted
- Date: 2025-10-27 JST
- Related ADRs: ADR-006 (calculate-before-reserve), ADR-007 (availability-check), ADR-008 (totalNetAfterDiscount)

---

## Context
`OrderService`はコンストラクタで割引ポリシー列（`List<DiscountPolicy>`）を受け取り、順序適用（`Volume → MultiItem → HighAmount → Cap`）で計算する。<br>
テストや呼び出し側から同じ`List`参照を使い回し／後から変更されると、`OrderService`内部のポリシー順・内容が外部の変異に引きずられる。その結果、以下のような非決定的な挙動が発生した。<br>
- 順序適用が崩れ、`MultiItem`/`HighAmount`が小計基準で計算される等の合計割引ブレ（例：`79→80`, `10434→10750`）
- Cap の注入が効かず、デフォルト構成で計算され続ける（例：`5750→10750`）
これらは**共有ミュータブル参照**が原因。テストコードも“外部”であり、外部の変異から**内部状態を防御する**必要がある。

---

##Decision
- `OrderService`の「ポリシー注入コンストラクタ」で防御的コピーを取る。

```java
public OrderService(ProductRepository products,
                    InventoryService inventory,
                    TaxCalculator tax,
                    List<DiscountPolicy> policies) {
    this.products = products;
    this.inventory = inventory;
    this.tax = tax;
    this.discountPolicies = List.copyOf(policies); // 不変スナップショット
}

public OrderService(ProductRepository products,
                    InventoryService inventory,
                    TaxCalculator tax) {
    this(products, inventory, tax, List.of(
        new VolumeDiscount(),
        new MultiItemDiscount(),
        new HighAmountDiscount(),
        new CapPolicy(new BigDecimal("0.30"))
    ));
}
```

- `discountPolicies`は不変コレクションとして保持し、**後からの差し替えAPI（setter）を提供しない**。
- 受け取った順序を**そのまま適用順**とする（順序は呼び出し側の“契約”）。

---

##Consequences
- メリット
	- 外部の変異（テスト含む）から内部状態を隔離でき、計算結果と順序適用が安定。
		- テストの実行順や他ケースの副作用に左右されない再現性を確保。
		- 設計上の“契約境界”が明確になり、保守性が上がる。
- デメリット
	- **受け取り直後にスナップショット固定**されるため、起動後にポリシーを差し替える動的要求には別手段（再生成・DI再構築）が必要。
		- `List.copyOf`は浅いコピー。個々の`DiscountPolicy`は原則ステートレスであることを前提とする（本設計ではステートレス）。

- 運用ルール
	- `DiscountPolicy`実装は**副作用なし・ステートレス**を維持する。
		- テストでポリシー列を使い回さない。各ケースで`List.of(...)`を新規作成する。
		- `DiscountEngine.applyInOrder`は**負の調整（Cap差し戻し）**を許容し`base = base.subtract(d)`を常に更新する。<br>
		
---

##Alternatives Considered
1. **参照のまま保持（現状維持）**
	- 非決定性・順序崩れが再発。却下。
2. **`new ArrayList<>(policies)`で可変コピー＋`Collections.unmodifiableList`**
	- ほぼ同等の効果。`List.copyOf`の方が簡潔で意図が明確。
3. **Builderパターンで登録を強制**
	- 過剰設計。現段階の要件では`copyOf`で十分。

---

##Tests
- **Regression（回帰）**:
	- 注入後に外部`policies.add(...)`/`clear()`を行っても、`OrderService`の結果・順序が変わらないこと。
- **順序固定**:
	- `Volume → MultiItem → HighAmount → Cap` の順で`applyInOrder`が基準更新（`base`）を行い、既存の期待値（例：`79`, `10434`, Cap注入時 `5750`）が安定的に再現されること。
- **Unnecessary stubbing が発生しない**こと（観点外の層はスタブしない）。

---

##Notes
- 本ADRは「**テストも外部**」という安全保障の観点を明文化するもの。
- 設計レビューでは“注入されたコレクションを**そのまま保持しない**”ことを原則とし、**防御コピー＋不変化**を標準とする。
