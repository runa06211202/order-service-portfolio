# ADR-004: 割引適用順序および Cap（上限30%）の適用方針

- Status: Accepted  
- Date: 2025-10-07 JST  
- Decision Drivers  
  - 仕様説明の明確化（順序が固定であることを保証）  
  - テスト容易性（境界値の検証を単純化）  
  - ビジネスロジックの一意性（計算結果が常に再現可能）  
  - 保守性（将来割引ルールを追加する際の拡張ポイントを明確化）  
  - 可観測性（どの割引が適用されたかを追跡しやすくする）

---

## Context
現状、注文ごとに複数の割引が発生するが、  
割引の**適用順序**と**上限（Cap）**の扱いが曖昧なままでは  
以下の問題が起きる：

- 計算順序によって合計金額が異なる（不定）  
- テストの期待値が一致しない  
- 「どの割引が効いたか」をログや画面で説明できない  
- Cap処理（合計割引率の上限）をいつ適用するかが不明瞭  

これらを防ぐため、割引適用の順序とCapの算出ルールを明文化する。

---

## Decision
- **適用順序**
  1. **VOLUME（数量割引）**  
     各行ごとに数量 `qty >= 10` の場合、行小計の 5% OFF。  
  2. **MULTI_ITEM（複数商品割引）**  
     異なる productId が 3 種以上の場合、注文小計から 2% OFF。  
  3. **HIGH_AMOUNT（高額割引）**  
     割引後小計が 100,000 以上の場合、さらに 3% OFF。  

  → 常にこの順序で適用する。

- **Cap（割引上限）**
　- Cap実装はPolicyで注入可能、既定は30%とする
  - 最終的な `totalDiscount` は `subtotalBeforeDiscount * capRate` を超えない。  
  - 超過する場合は `totalDiscount = subtotalBeforeDiscount * capRate` に丸める。  
  - 現行の割引率（5%+2%+3%）では理論上既定の30%に到達しないため、  
    **「到達不能の安全弁」として仕様上定義し、テストでは@Disabledで保持する。**
  - `capRate`を下げてのポリシー動作確認テストを実施する

- **適用順序の実装指針**
  - 割引適用ごとに中間値を保持：  
    `afterVolume → afterMulti → afterHigh → afterCap`  
  - 適用済み割引を `EnumSet<DiscountType>` で保持し、重複を排除。  
  - Capは全割引計算後に一度だけ適用。

---

## Consequences
- **テスト**  
  - 各割引単体および組合せパターン（V, M, H, VM, VH, MH, VMH）を固定順で検証可能。  
  - Capテストは `@Disabled("30% cap is a future guard")` として定義しておく。  

- **実装**  
  - 割引順序を変更する場合は ADR 改訂を必須とする。  
  - 適用ラベル（`["VOLUME", "MULTI_ITEM", "HIGH_AMOUNT"]`）は  
    順序どおりに収集して返却。

- **可観測性**  
  - ログやレスポンスで「どの割引が適用されたか」を明示できる。  
  - 将来的にルール追加（例：季節割引）時も、順序リストへの追加で済む。

- **ビジネス影響**  
  - Capを「上限保証」として扱うことで、不正割引やオーバー割引を防止。  
  - 金額丸めや端数発生時もCap基準に一貫性を保てる。

---

## Alternatives Considered
- **代替A：同時適用（すべて合算して一括計算）**  
  - 順序依存がなくなるが、説明・トレースが困難。却下。  
- **代替B：金額閾値に応じて優先度を動的変更**  
  - 複雑化し、境界テストが不安定になるため却下。  
- **代替C：Capを固定値（例：上限30,000円）で設定**  
  - 商品単価や件数によって不公平が生じるため却下。

---

## Notes
- **テスト例**
  - `appliesVolumeDiscount_whenQtyIs10()`  
  - `appliesMultiItemDiscount_when3Products()`  
  - `appliesHighAmountDiscount_whenTotalOver100000()`  
  - `doesNotExceedCap_evenWhenAllDiscountsApplied()`  
  - `disabledTest_capLimit_futureGuard()`  

- **補足**
  - Cap上限値（30%）は Config 等で外部定義しても良いが、  
    現行は固定値として仕様上明記する。  
  - 割引ラベルは EnumSet→List 化の際に順序保持されることを保証。

