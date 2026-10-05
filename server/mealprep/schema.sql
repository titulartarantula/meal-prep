CREATE TABLE IF NOT EXISTS recipes(
  id serial PRIMARY KEY,
  source text,
  source_url text UNIQUE,
  title text,
  servings int,
  data jsonb NOT NULL,
  ai_provider text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS plan(
  id serial PRIMARY KEY,
  week date NOT NULL,
  recipe_id int NOT NULL REFERENCES recipes,
  day smallint CHECK (day BETWEEN 0 AND 6),
  multiplier real NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS plan_week_idx ON plan(week);
CREATE TABLE IF NOT EXISTS products(
  code text PRIMARY KEY,
  name text,
  brand text,
  package_size text,
  first_seen timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS price_observations(
  id bigserial PRIMARY KEY,
  code text REFERENCES products,
  store_id text,
  price numeric(8,2),
  stock text,
  observed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS picks(
  key text PRIMARY KEY,
  product_code text NOT NULL,
  chosen_by text CHECK (chosen_by IN ('ai','user')),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS pick_history(
  id bigserial PRIMARY KEY,
  key text,
  product_code text,
  chosen_by text,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS carts(
  id serial PRIMARY KEY,
  pcx_cart_id text UNIQUE,
  store_id text,
  ai_provider text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS cart_weeks(
  cart_id int REFERENCES carts ON DELETE CASCADE,
  week date,
  PRIMARY KEY(cart_id, week)
);
CREATE TABLE IF NOT EXISTS cart_lines(
  id serial PRIMARY KEY,
  cart_id int REFERENCES carts ON DELETE CASCADE,
  item_key text,
  item_name text,
  need_qty real,
  need_unit text,
  product_code text REFERENCES products,
  quantity int,
  price_at_add numeric(8,2),
  status text,
  source text CHECK (source IN ('memory','ai','user','none'))
);

-- Drafts (2026-10-04): a cart is built in the app first (status building → ready), the user edits it,
-- then it is sent to PC Express (status sent, pcx_cart_id set, cart_weeks recorded).
-- Idempotent migrations for DBs created before drafts: pre-existing carts were sent immediately.
ALTER TABLE carts ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'sent';
ALTER TABLE carts ALTER COLUMN status SET DEFAULT 'building';
ALTER TABLE carts ADD COLUMN IF NOT EXISTS weeks date[];
ALTER TABLE carts ADD COLUMN IF NOT EXISTS error text;
ALTER TABLE carts ADD COLUMN IF NOT EXISTS progress_done int NOT NULL DEFAULT 0;
ALTER TABLE carts ADD COLUMN IF NOT EXISTS progress_total int NOT NULL DEFAULT 0;
ALTER TABLE carts ADD COLUMN IF NOT EXISTS sent_at timestamptz;
UPDATE carts c SET weeks = (SELECT array_agg(week ORDER BY week) FROM cart_weeks WHERE cart_id = c.id),
                   sent_at = COALESCE(sent_at, created_at)
  WHERE c.status = 'sent' AND c.weeks IS NULL AND EXISTS (SELECT 1 FROM cart_weeks WHERE cart_id = c.id);
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS position int;
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS item jsonb;
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS product jsonb;
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS alternatives jsonb NOT NULL DEFAULT '[]';
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS removed boolean NOT NULL DEFAULT false;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cart_lines_source_check'
                 AND pg_get_constraintdef(oid) LIKE '%user%') THEN
    ALTER TABLE cart_lines DROP CONSTRAINT IF EXISTS cart_lines_source_check;
    ALTER TABLE cart_lines ADD CONSTRAINT cart_lines_source_check CHECK (source IN ('memory','ai','user','none'));
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS cart_lines_cart_idx ON cart_lines(cart_id);

-- Ratings (2026-10-04): one rating per time cooked (plan entry); re-rating overwrites.
-- rating_history is append-only (every set/delete, with recipe + cooked date snapshot) for reports;
-- it has no FK so it survives a plan entry being deleted.
CREATE TABLE IF NOT EXISTS ratings(
  plan_id int PRIMARY KEY REFERENCES plan ON DELETE CASCADE,
  family smallint NOT NULL CHECK (family BETWEEN 1 AND 5),
  company text CHECK (company IN ('yes','maybe','no')),
  note text,
  rated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS rating_history(
  id bigserial PRIMARY KEY,
  plan_id int NOT NULL,
  recipe_id int,
  week date,
  day smallint,
  action text NOT NULL CHECK (action IN ('set','delete')),
  family smallint,
  company text,
  note text,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS rating_history_recipe_idx ON rating_history(recipe_id);
CREATE INDEX IF NOT EXISTS plan_recipe_idx ON plan(recipe_id);

-- Prep plans + cook cards (2026-10-04, stage 2). A plan is generated in the background (building → ready | failed);
-- input = snapshot of the entries it was made from (staleness + reports). Older plans are kept (history);
-- a newer ready plan replaces older plans' cook cards. prep_task_events is append-only (prep time vs estimate).
CREATE TABLE IF NOT EXISTS prep_plans(
  id serial PRIMARY KEY,
  weeks date[] NOT NULL,
  status text NOT NULL DEFAULT 'building' CHECK (status IN ('building','ready','failed')),
  error text,
  ai_provider text,
  input jsonb NOT NULL,
  warnings jsonb NOT NULL DEFAULT '[]',
  total_minutes int,
  progress_done int NOT NULL DEFAULT 0,
  progress_total int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
CREATE INDEX IF NOT EXISTS prep_plans_weeks_idx ON prep_plans USING gin(weeks);
CREATE TABLE IF NOT EXISTS prep_tasks(
  prep_plan_id int NOT NULL REFERENCES prep_plans ON DELETE CASCADE,
  task_id text NOT NULL,
  section text NOT NULL CHECK (section IN ('knife','sauces','proteins','pack')),
  position int NOT NULL,
  text text NOT NULL,
  serves jsonb NOT NULL DEFAULT '[]',
  est_minutes int NOT NULL DEFAULT 0,
  shelf_life text NOT NULL CHECK (shelf_life IN ('ok','day_of','freeze_then_thaw')),
  thaw text,
  contents jsonb NOT NULL DEFAULT '[]',
  flags jsonb NOT NULL DEFAULT '[]',
  done boolean NOT NULL DEFAULT false,
  done_at timestamptz,
  PRIMARY KEY(prep_plan_id, task_id)
);
CREATE TABLE IF NOT EXISTS prep_task_events(
  id bigserial PRIMARY KEY,
  prep_plan_id int NOT NULL,
  task_id text NOT NULL,
  done boolean NOT NULL,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS prep_task_events_plan_idx ON prep_task_events(prep_plan_id);
CREATE TABLE IF NOT EXISTS cook_cards(
  id serial PRIMARY KEY,
  prep_plan_id int NOT NULL REFERENCES prep_plans ON DELETE CASCADE,
  plan_id int NOT NULL REFERENCES plan ON DELETE CASCADE,
  recipe_id int,
  week date,
  day smallint,
  multiplier real,
  card jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(prep_plan_id, plan_id)
);
CREATE INDEX IF NOT EXISTS cook_cards_plan_idx ON cook_cards(plan_id);
