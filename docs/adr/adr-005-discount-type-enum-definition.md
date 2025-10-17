# ADR-005: 割引種別を Enum（DiscountType）で型定義する

- Status: Accepted
- Date: 2025-10-15 JST
- Decision Drivers
	- 割引ラベルを文字列で扱うと、誤記・重複・大小文字揺れのリスクがある<br>
	- 将来、管理UIや外部APIで割引名を扱う場合、型による保証が望ましい<br>
	- IDE補完やコンパイル時チェックを有効にし、ドメインの安全性を高めたい<br>

---

## Context
- これまで割引種別（"VOLUME"、"MULTI_ITEM"、"HIGH_AMOUNT"）を文字列リテラルで保持していた。

- そのためテストや比較時に "VOLUME" と "Volume" のような揺れが発生する危険があり、
enum による型安全化でこれを解消する。

- 割引ラベルは今後 OrderResult のAPI出力や管理UIのフィルタ機能などで再利用される見込みがある。

---

## Decision
- 割引種別を enum DiscountType として明示的に定義する：

```java
public enum DiscountType {
    VOLUME,
    MULTI_ITEM,
    HIGH_AMOUNT
}
```
- サービス内部では EnumSet<DiscountType> で保持し、公開時には List<DiscountType> の不変リストとして返却する。
- JSON 出力時はデフォルトの name() を使用。必要に応じて @JsonValue でラベル表現を追加する。

---

## Consequences
- `OrderResult` および関連DTOのフィールド型を `String` → `DiscountType` に変更。
- 期待値比較テストは文字列比較から enum 比較（`containsExactlyElementsOf`）へ移行。
- ラベルの重複防止は EnumSet によって自動保証される。
- API出力仕様は `["VOLUME", "MULTI_ITEM", "HIGH_AMOUNT"]` のように大文字定義に統一される。

---

## Alternatives Considered
- 従来どおり文字列で保持する案：
→ 誤記防止の仕組みがなく、IDE補完・型検査が効かないため却下。

---

## Notes / Verification
- 適用ラベルの重複防止を `EnumSet` で確認（テスト済）。
- 公開時の不変リスト化を確認（`UnsupportedOperationException` が発生すること）。
- 将来的にラベルを日本語表記にする場合は、`@JsonValue` または `ResourceBundle` により対応可能。
