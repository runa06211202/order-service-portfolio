# ADR-010: Cap 発動時のみ`DiscountType.CAP`をラベル出力する
- Status: Accepted
- Date: 2025-10-27 JST
- Related ADRs: ADR-004 (discount-order-and-cap) ADR-005 (discount-type-enum-definition) ADR-006 (calculate-before-reserve) ADR-009 (defensive-copy-for-injected-policies)

---

## Context
`OrderService`は割引計算を`DiscountPolicy`群に委譲し、
それぞれのポリシーが自分の適用有無と割引額を返す。

各ポリシーは`DiscountType`を持ち、計算時に
`OrderResult.appliedDiscounts`へ記録することで、
外部から「どの割引が発動したか」を可視化している。

このうち`CapPolicy`は割引の上限（既定30%）を超えた場合に差し戻しを行うものであり、
通常は“何も起こらない”ことの方が多い。
このため、CapPolicy のラベル (`DiscountType.CAP`) を
**常に出力するか、発動時のみ出力するか**を明確に定義する必要がある。

---

##Decision
- `DiscountType.CAP`は 上限が実際に発動したときのみ`appliedDiscounts`に追加する。
- 「発動」とは、CapPolicy の計算結果`discount(...)`が **非ゼロ（≠0）**を返した場合と定義する。
- 順序は既存割引と同様に、`VOLUME → MULTI_ITEM → HIGH_AMOUNT → CAP`の最後に配置する。
- Cap 未発動の場合、`appliedDiscounts`は Volume / MultiItem / HighAmount のみを保持し、Cap は含めない。

###実装例（DiscountEngine 内）

```java
for (var p : policies) {
    BigDecimal d = p.discount(req, products, base);
    if (d.compareTo(BigDecimal.ZERO) != 0) { // 非ゼロならラベル記録
        applied.add(p.type());
    }
    total = total.add(d);
    base  = base.subtract(d);
}
```

`CapPolicy.type()`実装

```java
@Override
public DiscountType type() {
    return DiscountType.CAP;
}
```

---

##Consequences
- メリット
	- **ノイズの少ない出力**
		未発動時に CAP が並ぶ混乱を避けられる。ユーザ／UI が「適用割引」を正しく理解できる。
		- **説明責任の明確化**差し戻しが起きた場合のみ CAP を明示するため、上限制御の存在を透明化できる。
		- **テスト観点の分離**金額系アンカー（数値確認）とラベル系アンカー（適用履歴確認）を独立して検証できる。
- デメリット/トレードオフ
	- ラベル数が入力によって変動するため、`appliedDiscounts`のテストには順序と件数の考慮が必要。
		- Cap 発動判定が「非ゼロ額」と結びついているため、計算誤差が極小値（1円未満）になる場合は丸め精度に注意。
- 運用ルール
	- Cap の「上限閾値」変更時には、**ラベル系テストを必ず併せて更新**すること。
		- `DiscountEngine`内のラベル収集条件は「`compareTo != 0`」を固定仕様とする。
		- `DiscountType`は ENUM のまま維持し、将来のラベル拡張もここに統一。

---

##Alternatives Considered
1. 常に CAP を出力
		- CapPolicy がリストに存在する限り常時ラベルに含める
				- 実際に発動していない割引を表示するのは誤解を招く。
2. Cap の発動有無を別フラグで返す
	- `OrderResult`に`capEngaged` booleanを追加
				- 出力構造が煩雑化し、ラベル一貫性が崩れる。
3. ラベルを全ポリシー共通で出力し、発動有無を別配列に分ける
	- `allDiscounts` + `appliedDiscounts` の二系統
				- 過剰設計で実利が少ない。

---

##Tests
- **未発動ケース**<br>
	入力：Volume + MultiItem + HighAmount で Cap に到達しない。<br>
	期待：`appliedDiscounts = [VOLUME, MULTI_ITEM, HIGH_AMOUNT]`<br>
- **発動ケース**<br>
	入力：Cap=5% を注入し、超過差し戻しを発生させる。<br>
	期待：`appliedDiscounts = [VOLUME, MULTI_ITEM, HIGH_AMOUNT, CAP]`<br>

---

##Notes
- 本ADRは、「Capは金額上限という安全装置であり、通常時は沈黙する」という設計哲学を明文化したものである。
- `DiscountPolicy`群はすべて**ステートレス**であり、`type()`は単一値を返す。
- Cap のラベル出力仕様は将来的な**ロギング・監査機能拡張**の基礎にもなる。
