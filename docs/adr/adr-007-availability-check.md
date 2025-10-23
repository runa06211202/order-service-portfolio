# ADR-007: 在庫可用性チェック導入と呼び出し順序の再定義（availability→calculate→reserve）
- Status: Accepted
- Date: 2025-10-23 JST
- Decision Drivers
  - 例外発生時に外部副作用（在庫確保）が残留しない整合性の確保
  - テスト容易性（純粋計算と副作用の段切り、順序の明示）
  - 将来拡張（割引ポリシー増加、税率ロジック変更）への耐性
  - 早期returnによる不要計算の回避（在庫不足時）
  - 実装の見通し（可読性）と責務分離

---

## Context

これまでの呼び出し順序は `find → 割引計算 → reserve×n → tax.addTax` であった。  
この順序では、割引・税計算は副作用より先行するが、在庫確保後に税計算等で例外が発生した場合、確保済み在庫の補償（解放）処理が必要となり、実装・運用の複雑度が上がる。また、仕様「在庫・税計算例外は上位へ伝播」により、例外を握りつぶさず即時中断するため、補償設計がないと副作用が取り残されうる。

評価観点としては、正確性（整合性・一貫性）、可読性、テスト容易性（VerifyCallsで順序ロック、モック最小化）、将来拡張（割引のStrategy化、Money導入）を重視する。CPU負荷はドメイン特性上、計算よりI/O（在庫API）が支配的であり、可用性チェック→計算→最終確保の二段構えが妥当と判断した。

---

## Decision

**在庫確保（副作用）を最後に寄せつつ、可用性チェックを事前に導入する**。呼び出し順序を次のとおり再定義する。
validateRequest → find(products) → checkAvailable×n（副作用なし）
→ calculateDiscounts（純粋計算）
→ calculateTax（純粋計算）
→ reserve×n（副作用）
→ save（副作用）

---

### インタフェース（Port）変更

```java
public interface InventoryService {
    // 追加（副作用なし、在庫の可用性のみ問い合わせ）
    boolean checkAvailable(String productId, int qty);

    // 既存（副作用あり、確保。失敗時は例外、成功時は戻り値なし）
    void reserve(String productId, int qty);
}
```
**ルール**
- 可用性NGが1件でもあれば、計算・確保・保存は行わず 例外を上位へ伝播 して終了する。
- 計算（割引・税）は純粋関数として扱い、副作用を持たない。
- 在庫確保は最後にまとめて行う。確保中の例外は上位へ伝播し、以降の保存等は行わない（補償は不要）。

---

##Consequences

###メリット
- 例外時に外部副作用が残らず、整合性が保たれる（補償設計不要）。
- 副作用（I/O）と計算（純粋関数）の段切りによりテストが容易（VerifyCallsで順序をロック可能）。
- 将来、割引ポリシー追加や税ロジック変更の影響範囲が計算層に限定される。
- 在庫不足は可用性チェックで早期検知でき、不要な計算・確保を避けられる。

###デメリット / トレードオフ
- Port追加（checkAvailable）に伴う実装・モック整備のコスト増。
- 在庫が十分でも、checkAvailable→reserve の二段API呼び出しになる（ただしI/O支配のため許容）。
- 既存テストの一部（「在庫落ち時に税を呼ばない」等）は順序見直しに追従して改修が必要。

###運用ルール
- checkAvailable は副作用を持たないこと（可用性の問い合わせのみ）。
- reserve は成功時例外なし、失敗時は例外で通知する（戻り値での成否は返さない）。
- OrderService 内の順序は validate → find → checkAvailable → calculate → reserve → save を維持すること。

---

###Alternatives Considered

1. 現状維持（find → discount → reserve×n → tax）
	- 却下理由：在庫確保後の例外で補償（release）が必要。整合性と実装複雑性の観点で不利。
2. 計算先行（validate → find → discount → tax → reserve×n → save）
	- 採用せず：副作用は最後に寄るが、早期return（在庫不足の即時中断）ができない。不要計算の発生と、在庫不足の検出タイミングが遅い。
3. 在庫先行（reserve×n → discount → tax）
	- 却下理由：後段の例外時に確保が残留し補償必須。整合性リスクが高い。

---

###Notes

実装指針：OrderService はメソッドを段切りで実装する。

```java
validateRequest(req);
var products = loadProducts(req);             // find
ensureAvailability(req.lines());              // checkAvailable×n（例外で早期return）
var subtotal = calculateSubtotal(products, req.lines());
var discount = calculateDiscount(subtotal);
var netAfter = subtotal.subtract(discount);
var taxAmt   = calculateTax(netAfter, req.region());
var gross    = netAfter.add(taxAmt);
reserveInventory(req.lines());                // reserve×n（副作用）
saveOrder(...);                               // save（副作用）
```

- テスト影響：
	- VerifyCalls：checkAvailable が reserve より前、calculate* は reserve より前であることを検証。
	- Abnormal：
		- checkAvailable が false を返したら例外伝播・副作用なし（verifyNoInteractions(inventory.reserve)）。
		- reserve 中の例外は伝播、save 未呼び出しを検証。
	- Normal：金額計算（割引・税）の期待値は従来どおり。順序が変わらないことを確認。
- 落とし穴：
	- 実装側で checkAvailable と reserve の対象・数量が一致しないと整合性崩れ。同一リクエストでの連続呼び出しを徹底。
	- 将来の部分確保や予約期限を扱う場合は、別ADR（予約トランザクションのライフサイクル）で補完する。
- 関連PR・テスト名（例）：
	- PR: feat: introduce InventoryService.checkAvailable and reorder pipeline
	- Tests:
		- VerifyCalls.checkAvailable_before_reserve_and_calculations_before_reserve
		- Abnormal.availabilityFalse_throws_noReserveNoSave
		- Abnormal.reserveThrows_propagates_noSave
		- Normal.happyPath_returnsExpectedTotals
