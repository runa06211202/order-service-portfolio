# ADR-011: PorductRepositoryのインフラ実装
- Status: Updated
- Date: 2025-11-02 JST

---

## Context
- 商品参照は頻度高・低レイテンシ要求
- 在庫/税と違い副作用なし

---

## Decision
- 当面は**内製インフラ（同プロセス）**で実装、Portは維持

---

## Consequences
- レイテンシ優位。スキーマ変更時はインメモリ→JDBC/JPAに段階移行

---

## Alternatives
- 外部カタログAPI（遅延・可用性の影響が大きく現段階では過剰）