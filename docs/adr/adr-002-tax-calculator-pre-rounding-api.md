# ADR-002: TaxCalculator に「丸め前の税額」を返す API を追加する

- Status: Accepted
- Date: 2025-10-07 JST
- Decision Drivers
  - テスト容易性（税率0地域・丸め境界の再現と検証を簡潔にする）
  - 可観測性・監査可能性（税額と総額の差分説明、照合作業の明確化）
  - 責務分離（税ロジックを TaxCalculator に集約し OrderService を単純化）
  - 将来の API 外部化（契約として税の算出過程を公開しやすくする）
  - 後方互換性（既存 `addTax` のシグネチャは変更しない）

## Context
現状の公開契約は `addTax(net, region, mode)`（税込合計を返す）のみであり、  
丸め前の税額（税抜×税率）を直接取得できない。  
そのため、以下の課題が生じる：
- 丸め差（例：端数処理や地域別規則）の説明がしづらい
- 税率0地域や境界値（端数が発生するケース）のテスト記述が冗長
- 集計・監査（「税額」「税込」それぞれの突合）で内部実装に依存しがち

OrderService 側で税率や丸めを再実装すると二重実装の温床になるため、  
TaxCalculator に「丸め前税額」を返す API を追加して契約で担保する。

## Decision
以下の 2 API で役割を分離する（既存 `addTax` は維持）。
```java
public interface TaxCalculator {
  /**
   * 丸め前の税額（税抜×税率）を返す。
   * 返却は scale=2 を基本とし、mode は中間丸めの規則に使用する（必要な地域のみ）。
   */
  BigDecimal calcTaxAmount(BigDecimal net, String region, RoundingMode mode);

  /**
   * 税込合計（net + 税）を返す。最終丸めをここで適用する。
   * 返却は公開境界規約に従い scale=0（円）に正規化されることを想定。
   */
  BigDecimal addTax(BigDecimal net, String region, RoundingMode mode);
}
```
- OrderService の利用方針
  - 最終値の確定は引き続き addTax のみを採用（公開境界は scale=0）。
  - calcTaxAmount は監査・ログ・テスト（期待値供給）等の可観測性用途で任意利用。
- スケール規約
  - calcTaxAmount は scale=2 を基本（ADR-001 の内部規約に整合）。
  - addTax の返却は scale=0（税込総額の公開規約）。
- 地域別規則（例）
  - 税率0地域は calcTaxAmount=0.00、addTax=net。
  - 地域固有の端数処理がある場合は mode の解釈を実装側で行う。

## Consequences
-テスト
  - 税率0 / 低額端数 / 高額境界などを calcTaxAmount で直接アサート可能。
  - OrderService テストでは「税の正否」自体は Tax のモックに委譲し、委譲有無と丸めモード渡しを検証。
- 監査・運用
  - ログに net / taxAmount / gross を別出力でき、照合が容易。
- 契約・API
  - OpenAPIで2エンドポイント（もしくは 2 メソッド）を定義。既存 addTax の互換は維持。
- 実装コスト
  - TaxCalculator 側の責務は増えるが、OrderService から税再実装が排除されるため全体複雑度は低下。

## Alternatives Considered
- 代替A：OrderService で税率表を持ち、税額を再計算
  - 二重実装・乖離リスクが高く却下。
- 代替B：addTax だけに統一し、税額は差分で求める
  - 丸め順序による誤差が説明できず、境界テストが脆い。
- 代替C：税率取得 API（getRate(region)）を追加
  - 実効税額は依然としてクライアント側で再計算が必要になり、責務分離に反する。

## Notes
- OpenAPI(例)
  ```yaml
  /tax/calc-amount:
  post:
    summary: Calculate pre-rounding tax amount
    requestBody: { net: number, region: string, mode: string } # HALF_UP 等
    responses: { 200: { taxAmount: number } } # scale=2
  /tax/add:
  post:
    summary: Add tax and return gross
    requestBody: { net: number, region: string, mode: string }
    responses: { 200: { gross: number } } # scale=0
  ```
-テスト例
  - calcTaxAmount_returnsZero_whenZeroTaxRegion()
  - calcTaxAmount_handlesRoundingBoundary()
  - orderService_delegatesToAddTax_withGivenMode_defaultHalfUp()
- 移行
  - 既存実装に calcTaxAmount を追加するだけで破壊的変更なし。
- 失敗モード
  - 引数不正（net<0、region blank）は IAE 等、TaxCalculator の契約内で統一。
