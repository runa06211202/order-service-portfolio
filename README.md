# order-pricing-portfolio

### ADR一覧
- [ADR-001: 金額スケールの統一](docs/adr/adr-001-rounding-and-scale-normalization.md)
- [ADR-002: TaxCalculator に「丸め前の税額」を返す API を追加する](docs/adr/adr-002-tax-calculator-pre-rounding-api.md)
- [ADR-003: Repository の findById は Optional を返す](docs/adr/adr-003-repository-optional-contract.md)
- [ADR-004: 割引適用順序および Cap（上限30%）の適用方針](docs/adr/adr-004-discount-order-and-cap.md)

## ビルドとテスト
このプロジェクトは Java 17 / Maven ベースで構築されています。  
以下のコマンドでビルドとテストを実行できます。
```bash
mvn -q -DskipTests=false test
```
テストレポートは target/surefire-reports に出力されます。
JaCoCo によるカバレッジ計測は mvn verify で有効になります。

シーケンス図 1枚（find→calc→reserve→tax→cap適用箇所）を後段で追加する予定
