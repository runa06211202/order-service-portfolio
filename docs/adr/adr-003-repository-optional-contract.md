# ADR-003: Repository の findById は Optional を返し、null を返さない（“存在しない”は Optional.empty）

- Status: Accepted  
- Date: 2025-10-07 JST  
- Decision Drivers  
  - Null安全性（NPE発生を防止し、防御的コードを減らす）  
  - テスト容易性（存在／非存在を簡潔に表現できる）  
  - 例外設計の一貫性（例外はドメイン境界でのみスロー）  
  - 可読性と明示性（nullの意味を曖昧にしない）  
  - ドメイン責務の明確化（“存在しない”をRepositoryが明示的に伝える）

---

## Context
現状、`ProductRepository.findById(id)` が null を返す可能性がある場合、  
呼び出し側の OrderService は null チェックを都度行う必要がある。  
この構造では：

- NullPointerException のリスクが残る  
- テストで「商品が存在しないケース」を表現するのに余分な if／null 設定が必要  
- “存在しない”ことの責務（例外にするのか、返却値で伝えるのか）が曖昧

ドメイン的には「存在しない商品」は“異常”だが、  
その判定と例外スローは **ユースケース（OrderService）側** の責務とする。  
Repository はあくまで「存在情報」を返すだけの純粋データアクセス層にする。

---

## Decision
- Repository 契約を以下のように定義する：

```java
public interface ProductRepository {
    Optional<Product> findById(String productId);
}
```
- null は絶対に返さない。
- 呼び出し側は以下のように取得する
```java
Product product = productRepository.findById(id)
    .orElseThrow(() -> new IllegalArgumentException("product not found: " + id));
```
- スロー責務は OrderService 側 に明示。
- Optional.empty() は「存在しなかった」ことを明示する。
- Repository 実装は DB・メモリ・外部API などどの形式でも同契約を維持。

## Consequences
- 安全性
  - null チェック漏れによる NPE が発生しない。
  - 例外スロー箇所が OrderService に統一され、テストで扱いやすくなる。
- テスト容易性
  - 存在しないケースは when(repo.findById("X")).thenReturn(Optional.empty()) で再現可能。
  - “not found” メッセージを固定文言にすることで assert 可能。
- 拡張性・再利用性
  - 他の Repository（CustomerRepository 等）にも同じパターンを適用できる。
  - Optional の契約があることで、上位層（Service）に共通防御ロジックを定義できる。
- デメリット
  - 呼び出し側で .orElseThrow() が増える。
  - null を返す古い実装からの移行時にラップ処理が必要。
 
## Alternatives Considered
- 代替A: Repository 内で例外をスローする
  - Repository の責務を超え、例外設計が層を跨ぐため却下。
- 代替B: null を許容し、呼び出し側で if チェック
  - 安全性・テスト性ともに悪化し、同一プロジェクト内で実装差が出るため却下。
- 代替C: Optional ではなく Result 型（Success / Failure）を返す
  - Java 標準ではなく、オーバーデザインになるため今回は採用しない。

## Notes
- テスト例
  - orderService_throwsException_whenProductNotFound()
  - orderService_fetchesProductSuccessfully_whenProductExists()
- 運用ルール
  - Repository 実装（InMemory, JPA など）は null を返さず Optional.empty() を返すことを CI で検証しても良い。
  - 例外メッセージ "product not found: <id>" は固定で、ログ・監査でも利用可。
- 将来拡張
  - 複数商品検索（findAllByIds）時も、存在しないIDは例外でなくリスト欠損として扱う方針。
