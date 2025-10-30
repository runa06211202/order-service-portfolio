# 用語集（Glossary）

- lines<br>
	注文行の配列。各要素は `{ productId, qty }`。順序は在庫確保呼び出し順にも利用。
- region<br>
	税計算で使う地域コード（例: `JP`）。空白・null はエラー。
- mode<br>
	税計算の丸めモード（`RoundingMode`）。null の場合は `HALF_UP` を既定とする。
- subtotal / totalNetBeforeDiscount<br>
	割引適用前の注文素合計（行小計の合計）。内部では `subtotalBase` とも呼ぶ。scale=2。
- totalDiscount<br>
	適用済み割引の合計額。`VOLUME`→`MULTI_ITEM`→`HIGH_AMOUNT` の順に加算し、最終的に Cap（30%）で上限。`scale=2`。
- totalNetAfterDiscount<br>
	割引後の税抜合計。`totalNetBeforeDiscount - totalDiscount`。`scale=2`。<br>
	一旦入出力から削除していたが、計算及び今後の拡張性を考え、含めるのが妥当なため再追加。
- totalTax<br>
	税額の合計。`TaxCalculator.calcTaxAmount(totalNetAfterDiscount, region, mode)` の結果を採用。`scale=2`。
- totalGross<br>
	税込合計。`addTax(netAfter, region, mode)`の戻り値を`totalGross`として採用、税額は`totalGross - totalNetAfterDiscount`として整合する。公開境界で `scale=0` に正規化。
- appliedDiscounts<br>
	適用された割引のラベル集合（例: `[VOLUME, MULTI_ITEM]`）。順序は適用順に準ずるが比較は順不同でも可。<br>
	DiscountType型のEnumで定義(ADR-005)
- VOLUME（数量割引）<br>
	各行の `qty >= 10` に対し、その行小計の 5% を割引。行単位で判定・適用。
- MULTI_ITEM（複数商品割引）<br>
	異なる `productId` が 3 種以上のとき、注文小計から 2% 割引。
- HIGH_AMOUNT（高額割引）<br>
	割引後小計が `>= 100,000` のとき、さらに 3% 割引。
- Cap（割引上限）<br>
	`totalDiscount <= totalNetBeforeDiscount * capRate`。現行条件では理論到達困難。テストは `@Disabled` で旗として保持。
- CapPolicy<br>
	上記のCap上限をポリシー化。差し替え可能な安全弁として実装、発動時はラベル付与(ADR-010) `scale=2`
- ProductRepository<br>
	`Optional<Product> findById(id)` を提供。`null` は返さない。存在しない場合は `Optional.empty()`。
- InventoryService<br>
	`checkAvailable(productId, qty)`と`reserve(productId, qty)` を提供。availability → subtotal/discount → tax → reserveの順で呼出。availabilityの例外時は早期リターンして例外伝播。reserveの例外時は注文不成立。
- TaxCalculator<br>
	`calcTaxAmount(net, region, mode)` と `addTax(net, region, mode)` を提供。丸め規則は実装依存。
- スケール規約<br>
	内部の金額は `scale=2`、`totalGross` は公開境界で `scale=0` に正規化（ADR-001）。
- 例外ポリシー<br>
	`lines null/empty`、`qty <= 0`、`region null/blank`、`product not found` は `IllegalArgumentException`。在庫・税の例外は伝播。

###命名対応表（実装／ドキュメント）
- `subtotalBase` ↔ `totalNetBeforeDiscount`
- `subtotalAfter*` ↔ 割引適用後の中間値（ドキュメントでは説明用にのみ使用）
- `labels` ↔ `appliedLabels` / `appliedDiscounts`<br>
	参考: ADR-001（スケール正規化）、ADR-002（税APIの分離）、ADR-004（割引順序とCap）
