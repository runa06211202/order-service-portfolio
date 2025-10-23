# ADR-006: 副作用を後段に寄せた呼び出し順序の再定義（calculate → reserve）

- Status: Accepted
- Date: 2025-10-23 JST
- Decision Drivers
  - 例外発生時に外部副作用（在庫確保）が残留しない整合性の確保
  - 計算処理を純粋関数化することによるテスト容易性と責務分離
  - 呼び出し順序を明示することでリファクタリング時の安全性を高める
  - 将来の割引・税計算ロジック拡張に備えた柔軟性の確保

---

## Context

これまでの呼び出し順序は `find → 割引計算 → reserve×n → tax.addTax` であった。  
この構造では、在庫確保後に税計算や割引適用で例外が発生した場合、  
確保済み在庫が残留する危険がある。  

一方で、既存ポート（`InventoryService#reserve`）は副作用を伴うため、  
処理全体を安全に保つには副作用を後段に寄せ、計算部分を独立させるのが望ましい。  

本サイクルではPortの追加は行わず、  
**「計算を先行させ、副作用（reserve）を最後に呼ぶ」** という最小変更で整合性とテスト容易性を得る。

---

## Decision

呼び出し順序を以下のように再定義する。  
在庫確保を最後に寄せ、計算部分を純粋関数として扱う。
validateRequest
→ find(products)
→ calculateSubtotal
→ calculateDiscount
→ calculateTax
→ reserveInventory (副作用)

### 実装ルール

- 副作用を持つ処理（`reserveInventory`, `saveOrder`）は**最後**にまとめる。
- 割引・税計算などの金額処理は**純粋関数（副作用なし）**で行う。
- 在庫確保中に例外が発生した場合は**上位へ伝播**し、保存処理を行わない。
- 既存テストの `VerifyCalls` においては、`calculate*` が `reserve` より前に呼ばれることを検証する。

---

## Consequences

### メリット
- 計算と副作用の段切りにより、単体テストで**純粋関数を独立検証**可能。
- 税や割引の拡張に伴う影響範囲が限定され、責務の分離が明確になる。
- 例外発生時の副作用残留がなくなり、整合性が向上する。
- 呼び出し順序を固定できるため、`VerifyCalls` テストでロジックの正当性を保証可能。

### デメリット
- 在庫不足の早期検出（可用性チェック）が行えず、在庫が足りない場合でも計算処理を実行する。
- Portの構造が現時点では単一（`reserve`）のため、将来的な拡張余地が残る。
- 例外処理が上位伝播となるため、リトライ制御は呼び出し側（上位層）の責務となる。

---

## Alternatives Considered

1. **現状維持（find → discount → reserve → tax）**  
   - 却下理由：副作用が前段にあり、在庫確保後の例外発生で整合性が崩れる。

2. **B案（availability → calculate → reserve）**  
   - 採用見送り（次フェーズに拡張予定）  
   - 利点：在庫可用性を事前チェックして早期returnできる。  
   - 欠点：Port追加が必要で影響範囲が広い。

---
## Notes

- 本ADRは段階的導入の**Step 1（計算を先行させ、副作用を最後に寄せる）**に対応する。
- 次フェーズでは `InventoryService#checkAvailable(String, int): boolean` を追加し、  
  早期returnを実現する **B案（availability → calculate → reserve）** を導入予定。
- 将来の拡張時、既存の `inventoryThrows_*` テストは `checkAvailable` の導入に合わせて見直す。
- 関連ブランチ: `feature/discount-tax`
- 関連テスト:  
  - `VerifyCalls.calculate_before_reserve`  
  - `Abnormal.inventoryThrows_propagates_noSave`  
  - `Normal.happyPath_returnsExpectedTotals`

- 関連ADR
	- [ADR-007: 在庫可用性チェック導入と呼び出し順序の再定義（availability→calculate→reserve）](docs/adr/adr-007-availability-check.md)
---

この段階で副作用の順序を確定させ、  
在庫チェック導入（B案）にスムーズに移行できる基盤を整える。

