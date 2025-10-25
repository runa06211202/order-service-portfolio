# 仕様要件（棚卸し）

## 概要
- ユースケース：複数商品の注文を受け、割引を適用し、税込合計を返す。
- 外部依存：ProductRepository / InventoryService / TaxCalculator
- テスト目標：正常・異常・境界、分岐網羅、モック/スタブ、ArgumentCaptor、呼び出し順序、しきい値チェック

## 入出力
入力: lines, region, mode  
出力: totalNetBeforeDiscount, totalDiscount, totalNetAfterDiscount, totalTax, totalGross, appliedLabels

## 割引ルール（順序固定）
1. VOLUME: 各行 qty≥10 の行に 5% OFF  
2. MULTI_ITEM: 異なる商品3種以上で 2% OFF  
3. HIGH_AMOUNT: 割引後小計≥100,000 なら 3% OFF  
4. 合計割引は素合計の30%を上限（CapPolicy注入にて設定可能。注入無しの場合既定の30％）

## 例外ポリシー
- lines が null / 空 → IllegalArgumentException  
- qty ≤ 0 → IllegalArgumentException  
- region が null / 空白 → IllegalArgumentException  
- 商品が1件でも未取得 → IllegalArgumentException("product not found: id")  
- 在庫・税計算例外は上位へ伝播

## 丸め・スケール
- 金額は BigDecimal(scale=2)、税込は scale=0  
- 税計算時の丸めモードは mode 指定（null時は HALF_UP）

## 呼び出し順序
validate → checkAvailable → discountCalc → taxCalc → reserve