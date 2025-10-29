# order-pricing-portfolio

### ADR一覧
- [ADR-001: 金額スケールの統一](docs/adr/adr-001-rounding-and-scale-normalization.md)
- [ADR-002: TaxCalculator に「丸め前の税額」を返す API を追加する](docs/adr/adr-002-tax-calculator-pre-rounding-api.md)
- [ADR-003: Repository の findById は Optional を返す](docs/adr/adr-003-repository-optional-contract.md)
- [ADR-004: 割引適用順序および Cap（上限30%）の適用方針](docs/adr/adr-004-discount-order-and-cap.md)
- [ADR-006: 副作用を後段に寄せた呼び出し順序の再定義（calculate → reserve）](docs/adr/adr-006-calc-before-reserve.md)
- [ADR-007: 在庫可用性チェック導入と呼び出し順序の再定義（availability→calculate→reserve）](docs/adr/adr-007-availability-check.md)
- [ADR-008: 割引後小計（totalNetAfterDiscount）の応答フィールド追加](docs/adr/adr-008-add-totalNetAfterDiscount-to-response-schema.md)
- [ADR-009: 割引ポリシー注入時の防御コピー（List.copyOf）導入](docs/adr/adr-009-defensive-copy-for-injected-policies(List.copyOf).md)
- [ADR-010: Cap 発動時のみ`DiscountType.CAP`をラベル出力する](docs/adr/adr-010-cap-labels-when-engaged.md)

## ビルドとテスト
このプロジェクトは Java 17 / Maven ベースで構築されています。  
以下のコマンドでビルドとテストを実行できます。

```bash
mvn -q -DskipTests=false test
```
テストレポートは target/surefire-reports に出力されます。
JaCoCo によるカバレッジ計測は mvn verify で有効になります。

## 処理順
validate→checkAvailable → discountCalc → taxCalc → reserve

シーケンス図 1枚（find→calc→reserve→tax→cap適用箇所）を後段で追加する予定
