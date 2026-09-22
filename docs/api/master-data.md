# Master data

Implemented catalog paths: `/api/products`, `/api/customers`, `/api/suppliers`, `/api/container-types`, `/api/payment-methods`, `/api/expense-categories`.

Each supports GET (list), POST (create), and PUT `/{id}` (update). There is no hard-delete endpoint. All writes require SETTINGS_MANAGE. Reads require SETTINGS_MANAGE or the relevant workflow capability: SALES_CREATE for sales reference data; PURCHASES_VIEW/PURCHASES_CREATE for suppliers; EXPENSES_CREATE for expense categories. Employee inline customer creation will be a separate, narrowly authorized workflow when invoices are implemented.

GET accepts `q` (prefix search by name, product code or customer phone), `page` (zero-based, 50 records), and `activeOnly`. It returns `{items, hasMore}`. Customers additionally accept `phone` for exact normalized lookup, including inactive matches. Phone normalization removes formatting and converts Arabic digits but does not guess country-code equivalence. The seeded walk-in customer has stable UUID `00000000-0000-0000-0000-000000000001`, no phone, and cannot be changed.

Common write fields: name, active, version (0 on creation). Updates must carry the last returned version; stale writes return EDIT_CONFLICT (409). Record IDs and audit actors are server-derived. The current JDBC response DTO mapping uses snake_case for catalog-specific fields; request fields use camelCase.

| Catalog | Additional request fields |
| --- | --- |
| Products | code, category (SPICE/HONEY/OTHER), trackingType (WEIGHT/PIECE), price, notes |
| Customers/suppliers | phone, address, notes |
| Container types | price, notes |
| Payment methods | isDefault |
| Expense categories | none |

Prices are positive decimals with at most 2 fraction digits and are returned as strings. Product cost/WAC and stock are not exposed or editable through these endpoints. Spices use kg, honey uses pieces, other products may use either. When transaction workflows are added, changes to established product units must be guarded against incompatible historical records.

Names are unique ignoring case for payment methods, container types and expense categories. Product codes are unique ignoring case. Customer normalized phone is unique even for inactive records; phone is optional. Non-unique constraint failures return structured validation errors without raw database details.

Payment-default changes acquire a database lock, clear the previous default, increment its version, and audit both records atomically. The current default cannot be deactivated or unset until another active method replaces it.

All successful writes append before/after JSON to master_data_audit within the transaction. Catalog SQL identifiers come only from a closed server enum; all user input uses bound SQL parameters.

Frontend forms store unsaved drafts in IndexedDB by user/catalog/record. Closing or losing authentication preserves them. Discard is explicit and confirmed. These are drafts, not an offline synchronization queue: saving requires a confirmed server response. Full idempotent accounting outbox synchronization is a later milestone. After an uncertain network save, check the list before retrying creation.
