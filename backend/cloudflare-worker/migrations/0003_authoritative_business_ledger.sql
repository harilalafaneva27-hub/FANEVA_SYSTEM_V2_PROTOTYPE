PRAGMA foreign_keys = ON;

-- Canonical V2 business state. All monetary values are whole MGA stored as
-- SQLite INTEGERs; the Worker remains responsible for deriving every total.
-- IDs are opaque server identifiers, never Android Room auto-increment IDs.
CREATE TABLE IF NOT EXISTS business_products (
  product_id TEXT PRIMARY KEY NOT NULL,
  product_code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 160),
  category TEXT NOT NULL DEFAULT '',
  purchase_price_mga INTEGER NOT NULL CHECK (
    typeof(purchase_price_mga) = 'integer' AND purchase_price_mga BETWEEN 0 AND 9007199254740991
  ),
  selling_price_mga INTEGER NOT NULL CHECK (
    typeof(selling_price_mga) = 'integer' AND selling_price_mga BETWEEN 0 AND 9007199254740991
  ),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS business_customers (
  customer_id TEXT PRIMARY KEY NOT NULL,
  display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 160),
  phone TEXT NOT NULL DEFAULT '',
  address TEXT NOT NULL DEFAULT '',
  credit_limit_mga INTEGER NOT NULL CHECK (
    typeof(credit_limit_mga) = 'integer' AND credit_limit_mga BETWEEN 0 AND 9007199254740991
  ),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS business_stock (
  store_id TEXT NOT NULL CHECK (store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  product_id TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity BETWEEN 0 AND 2147483647),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (store_id, product_id),
  FOREIGN KEY (product_id) REFERENCES business_products(product_id)
);

CREATE TABLE IF NOT EXISTS business_sales (
  sale_id TEXT PRIMARY KEY NOT NULL,
  sale_number TEXT NOT NULL UNIQUE,
  store_id TEXT NOT NULL CHECK (store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  customer_id TEXT,
  actor_principal_id TEXT NOT NULL,
  sale_type TEXT NOT NULL CHECK (sale_type IN ('CASH', 'CREDIT')),
  total_mga INTEGER NOT NULL CHECK (typeof(total_mga) = 'integer' AND total_mga BETWEEN 0 AND 9007199254740991),
  paid_mga INTEGER NOT NULL CHECK (typeof(paid_mga) = 'integer' AND paid_mga BETWEEN 0 AND 9007199254740991),
  remaining_mga INTEGER NOT NULL CHECK (typeof(remaining_mga) = 'integer' AND remaining_mga BETWEEN 0 AND 9007199254740991),
  payment_method TEXT NOT NULL CHECK (payment_method IN ('CASH', 'MVOLA', 'ORANGE_MONEY', 'AIRTEL_MONEY', 'BANK', 'OTHER')),
  status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'CANCELLED')),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at INTEGER NOT NULL,
  CHECK (paid_mga <= total_mga),
  CHECK (remaining_mga = total_mga - paid_mga),
  CHECK ((sale_type = 'CASH' AND remaining_mga = 0 AND customer_id IS NULL)
      OR sale_type = 'CREDIT'),
  FOREIGN KEY (customer_id) REFERENCES business_customers(customer_id),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id)
);

CREATE INDEX IF NOT EXISTS idx_business_sales_store_created
  ON business_sales(store_id, created_at);

CREATE TABLE IF NOT EXISTS business_sale_lines (
  sale_line_id TEXT PRIMARY KEY NOT NULL,
  sale_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  product_code_snapshot TEXT NOT NULL,
  product_name_snapshot TEXT NOT NULL,
  unit_price_mga INTEGER NOT NULL CHECK (typeof(unit_price_mga) = 'integer' AND unit_price_mga BETWEEN 0 AND 9007199254740991),
  quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity BETWEEN 1 AND 2147483647),
  line_total_mga INTEGER NOT NULL CHECK (typeof(line_total_mga) = 'integer' AND line_total_mga BETWEEN 0 AND 9007199254740991),
  FOREIGN KEY (sale_id) REFERENCES business_sales(sale_id),
  FOREIGN KEY (product_id) REFERENCES business_products(product_id),
  CHECK (line_total_mga = unit_price_mga * quantity)
);

CREATE INDEX IF NOT EXISTS idx_business_sale_lines_sale
  ON business_sale_lines(sale_id);

-- A credit sale adds a positive ledger delta; a debt payment adds a negative
-- delta. The current debt is always derived from this server-owned ledger.
CREATE TABLE IF NOT EXISTS business_customer_ledger (
  ledger_entry_id TEXT PRIMARY KEY NOT NULL,
  customer_id TEXT NOT NULL,
  sale_id TEXT,
  payment_id TEXT,
  entry_type TEXT NOT NULL CHECK (entry_type IN ('CREDIT_SALE', 'DEBT_PAYMENT', 'ADJUSTMENT')),
  delta_mga INTEGER NOT NULL CHECK (typeof(delta_mga) = 'integer' AND delta_mga BETWEEN -9007199254740991 AND 9007199254740991),
  actor_principal_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES business_customers(customer_id),
  FOREIGN KEY (sale_id) REFERENCES business_sales(sale_id),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id),
  CHECK ((entry_type = 'CREDIT_SALE' AND delta_mga >= 0)
      OR (entry_type = 'DEBT_PAYMENT' AND delta_mga < 0)
      OR entry_type = 'ADJUSTMENT')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_customer_ledger_sale_once
  ON business_customer_ledger(sale_id) WHERE sale_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_customer_ledger_payment_once
  ON business_customer_ledger(payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_customer_ledger_customer_time
  ON business_customer_ledger(customer_id, created_at);

CREATE TABLE IF NOT EXISTS business_debt_payments (
  payment_id TEXT PRIMARY KEY NOT NULL,
  customer_id TEXT NOT NULL,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga) = 'integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  payment_method TEXT NOT NULL CHECK (payment_method IN ('CASH', 'MVOLA', 'ORANGE_MONEY', 'AIRTEL_MONEY', 'BANK', 'OTHER')),
  note TEXT NOT NULL DEFAULT '',
  actor_principal_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES business_customers(customer_id),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id)
);

CREATE TABLE IF NOT EXISTS business_transfers (
  transfer_id TEXT PRIMARY KEY NOT NULL,
  product_id TEXT NOT NULL,
  from_store_id TEXT NOT NULL CHECK (from_store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  to_store_id TEXT NOT NULL CHECK (to_store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity BETWEEN 1 AND 2147483647),
  actor_principal_id TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'REJECTED')),
  created_at INTEGER NOT NULL,
  FOREIGN KEY (product_id) REFERENCES business_products(product_id),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id),
  CHECK (from_store_id <> to_store_id)
);

CREATE TABLE IF NOT EXISTS business_stock_movements (
  movement_id TEXT PRIMARY KEY NOT NULL,
  store_id TEXT NOT NULL CHECK (store_id IN ('A1', 'A2', 'A3', 'A4', 'A5')),
  product_id TEXT NOT NULL,
  movement_type TEXT NOT NULL CHECK (movement_type IN ('SALE', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT', 'RECEIPT')),
  quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta) = 'integer' AND quantity_delta BETWEEN -2147483647 AND 2147483647 AND quantity_delta <> 0),
  reference_id TEXT NOT NULL,
  actor_principal_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (product_id) REFERENCES business_products(product_id),
  FOREIGN KEY (actor_principal_id) REFERENCES auth_principals(principal_id)
);

CREATE INDEX IF NOT EXISTS idx_stock_movements_store_time
  ON business_stock_movements(store_id, created_at);

CREATE VIEW IF NOT EXISTS business_customer_balances AS
  SELECT c.customer_id,
         COALESCE(SUM(l.delta_mga), 0) AS current_debt_mga,
         c.credit_limit_mga
    FROM business_customers c
    LEFT JOIN business_customer_ledger l ON l.customer_id = c.customer_id
   GROUP BY c.customer_id, c.credit_limit_mga;
