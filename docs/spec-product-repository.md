# 仕様要件

## 概要
- 目的：商品マスタを`Order`モジュールから低レイテンシで参照する
- 境界：Port `ProductRepository`（outbound）に対するインフラ実装を提供
- 初期実装：`ProductRepositoryInMemory`（起動時プリロード）

## 入出力
- Interface: Optional<Product> findById(String productId)
- Product フィールド：id:String / name:String / price:BigDecimal(scale=2)
- 振る舞い：
	- 見つからない場合は Optional.empty()（OrderService 側で IAE）
		- 価格は scale=2 を保証（ADR-001 準拠）

## 例外ポリシー
- `findById` は例外を投げない（接続失敗等の概念なし）
- OrderService が `.orElseThrow(() -> IAE("product not found: ..."))` を一元実装

## 非機能要件
- レイテンシ：ミリ秒未満（同プロセス・インメモリ）
- 変更頻度：低（本ポートフォリオでは読み取り専用）
- 将来拡張：JDBC/JPA 実装／外部カタログAPI実装に差し替え可能（同一Port）

## テスト戦略（最小）
- Anchor: 既知ID→Present、未知ID→Empty
- Guard: `null` ID なら `Optional.empty()`（※今回は省略可）
- Regression: 価格の scale=2 を保証（プリロード時点で正規化）

## 配線
- `OrderApplicationService` コンストラクタ／DIで `ProductRepositoryInMemory` を注入
- README に「実装差し替え方」を追記