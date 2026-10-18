# University Books have no Owner

A University Book is stored as `books.added_by IS NULL`, not as a book whose `added_by` points at an Admin. `added_by` means ownership — it is what the triggers check before letting a Teacher edit or delete a book — and Admins never need ownership, since `books.edit` and `books.delete` cover every book. Deriving "University" from the adder's current Role instead would make books flip between "University" and a person's name whenever someone is promoted or demoted, would need a separate rule for what becomes of a deleted user's books, and would turn the single "University" entry of the "Added by" filter into a list of every Admin's id. With `NULL`, a Teacher's account being deleted simply turns their books into University Books (`ON DELETE SET NULL`).

## Consequences

- Which Admin added a University Book is not recorded. If that is ever needed, it belongs in a separate audit column, not in `added_by`.
