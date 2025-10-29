# ADR-008:割引後小計（totalNetAfterDiscount）の応答フィールド追加
- Status: Updated
- Date: 2025-10-29 JST
- Related ADRs: ADR-006 (calculate-before-reserve), ADR-007 (availability-check)

---

## Context
現行の`OrderResult`には`totalNetBeforeDiscount`（割引前小計）と`totalDiscount`（合計割引額）は存在するが、
その差分にあたる「割引後小計（課税対象額）」は含まれていなかった。

しかし税計算は常に「割引後金額」を基準に行われるため、
レスポンス上でこの値が明示されていないと、
totalTax や totalGross がどの段階の金額を基に算出されたかを
利用者やレビューアが即座に判断できない。

また、割引や丸め処理の増加に伴い、
数値検証・会計監査・ユニットテストで「どの段階の金額が基準か」を
確認する手段が必要となっていた。

---

##Decision
`OrderResult`に`totalNetAfterDiscount`フィールドを追加する。
この値は「割引後小計（＝課税対象金額）」を表す。
下記の式を不変条件として保持する。<br>

```java
	totalNetAfterDiscount = totalNetBeforeDiscount - totalDiscount  
	totalGross = totalNetAfterDiscount + totalTax
```

- totalGross は税計算Port（TaxCalculator）の addTax() を用いて算出する。
- addTax() の戻り値は totalNetAfterDiscount + totalTax に等価であるが、
  税額および丸め処理は Port 内部の実装責務とする。
  
- 本項目は出力専用（読み取り専用）とし、
クライアントはこの値を用いて税額計算結果の妥当性を検証できる。<br>

---

##Consequences
- メリット
	- 金額パイプライン（before → after → tax → gross）が一目で追える。<br>
		- 丸め誤差や割引適用後の検証が容易になる。<br>
		- 会計監査・デバッグ時の比較がシンプルになる。<br>
		- ユニットテストに不変条件を追加できる。<br>
	
- デメリット
	- フィールドが1つ増えるため冗長に見える可能性。<br>
		- クライアント側で古いスキーマとの互換性対応が必要な場合がある（今回の用途では影響軽微）。<br>

---

##Alternatives Considered
1. 算出省略（ドキュメントのみ）
	- 利用者が before - discount で計算可能。<br>
			- ただし即読性が低下し、tax・gross の基準が曖昧になる。<br>
			- 却下。
2. grossBase のような抽象名で統一
	- 金額の意味が不明確になり、監査時の誤解を招く。<br>
			- 却下。

---

##Notes

- 本変更は OrderResult のレコード定義のみを拡張し、
計算ロジックは既存のパイプライン（割引→税→在庫）を維持する。
- 新しい不変条件の検証を Normal.endToEnd_happyPath_invariants テストに追加。
- 今後、Money 型導入や丸め誤差検証を追加する際の基準値として利用する。
