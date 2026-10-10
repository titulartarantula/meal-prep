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

-- Weekly staples (2026-10-05, milestone B2): items bought most weeks (milk, eggs …), offered at the top of the
-- shopping list and merged into it like recipe lines (POST /list {staples: [ids]}). qty without a unit = packs
-- ("1" = one carton); with a unit it is an amount. last_bought is a manual hint; GET /staples prefers the newest
-- sent cart line for the item. The three starter rows are seeded once (a household that deletes them all keeps
-- an empty list): `seeds` records which one-time seeds have run.
CREATE TABLE IF NOT EXISTS staples(
  id serial PRIMARY KEY,
  name text NOT NULL,
  qty numeric,
  unit text,
  weekly boolean NOT NULL DEFAULT true,
  position int NOT NULL DEFAULT 0,
  last_bought date,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS seeds(
  name text PRIMARY KEY,
  at timestamptz NOT NULL DEFAULT now()
);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM seeds WHERE name = 'staples') THEN
    IF NOT EXISTS (SELECT 1 FROM staples) THEN
      INSERT INTO staples(name, qty, position) VALUES ('2% milk', 1, 0), ('eggs', 1, 1), ('lemonade', 1, 2);
    END IF;
    INSERT INTO seeds(name) VALUES ('staples') ON CONFLICT DO NOTHING;
  END IF;
END $$;

-- Recipe sources (2026-10-05, 0.4.2): where a recipe comes from, editable in the app. source_kind nyt | book | other;
-- source_title = the cookbook (null = not known yet: "Unknown book"); source_ref = page(s), free text. Backfill once
-- (only rows still null): an NYT link → nyt, a photo import → book with no title (never guessed), anything else → other.
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_kind text;
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_title text;
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_ref text;
UPDATE recipes SET source_kind = CASE WHEN source_url LIKE '%nytimes.com%' THEN 'nyt' WHEN source = 'photo' THEN 'book'
                                      ELSE 'other' END
  WHERE source_kind IS NULL;
ALTER TABLE recipes ALTER COLUMN source_kind SET DEFAULT 'other';
ALTER TABLE recipes ALTER COLUMN source_kind SET NOT NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'recipes_source_kind_check') THEN
    ALTER TABLE recipes ADD CONSTRAINT recipes_source_kind_check CHECK (source_kind IN ('nyt','book','other'));
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS recipes_source_idx ON recipes(source_kind, lower(source_title));

-- Book details (2026-10-05, 0.4.3): the author(s) and ISBN of a recipe's cookbook, filled when the app's "Which book?"
-- search suggestion is picked (GET /books/search). Null = not known; only kept with a source_title.
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_author text;
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_isbn text;

-- Stale carts (2026-10-06, 0.8.1): a fingerprint of the shopping-relevant plan (week, recipe, scale; not the night) of
-- the cart's weeks when the draft was made. A cart whose weeks no longer match is stale: the week is no longer carted.
-- Null = made before 0.8.1, unknown: never stale (history isn't rewritten).
ALTER TABLE carts ADD COLUMN IF NOT EXISTS plan_fingerprint text;

-- Stable recipe id (2026-10-06, import/export): the schema.org identifier (urn:uuid:<uid>) and the de-dup key across
-- libraries. Backfilled once (only rows still null); new rows get one from the default; an import may bring its own.
ALTER TABLE recipes ADD COLUMN IF NOT EXISTS uid uuid;
UPDATE recipes SET uid = gen_random_uuid() WHERE uid IS NULL;
ALTER TABLE recipes ALTER COLUMN uid SET DEFAULT gen_random_uuid();
ALTER TABLE recipes ALTER COLUMN uid SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS recipes_uid_idx ON recipes(uid);

-- Imported ratings (2026-10-06, import/export): times cooked + ratings + notes brought in from another library's export
-- (mealprep:ratings.entries). They count in the recipe's rating summary like the household's own (Favourites, stars,
-- Good for company); they are not plan entries (no week/night). fingerprint = hash of the entry (date, multiplier,
-- family, company, note, rated_at): re-importing the same file adds nothing.
CREATE TABLE IF NOT EXISTS imported_ratings(
  id bigserial PRIMARY KEY,
  recipe_id int NOT NULL REFERENCES recipes ON DELETE CASCADE,
  cooked_on date,
  multiplier real NOT NULL DEFAULT 1,
  family smallint CHECK (family BETWEEN 1 AND 5),      -- null = cooked, not rated
  company text CHECK (company IN ('yes','maybe','no')),
  note text,
  rated_at timestamptz,
  origin text NOT NULL,                                -- 'mealprep' (another Meal Prep library)
  fingerprint text NOT NULL,
  imported_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(recipe_id, fingerprint)
);

-- Import jobs (2026-10-06): POST /recipes/import with dry_run=false saves in the background (the AI tidies foreign
-- ingredient lines). report = the per-item results so far (the GET /imports/{id} body); file_sha + choices_sha make a
-- retried apply of the same file and choices return the same job instead of starting another.
CREATE TABLE IF NOT EXISTS import_jobs(
  id serial PRIMARY KEY,
  file_sha text NOT NULL,
  choices_sha text NOT NULL,
  status text NOT NULL DEFAULT 'running' CHECK (status IN ('running','done','failed')),
  report jsonb NOT NULL,
  progress_done int NOT NULL DEFAULT 0,
  progress_total int NOT NULL DEFAULT 0,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
CREATE INDEX IF NOT EXISTS import_jobs_key_idx ON import_jobs(file_sha, choices_sha);

-- Recipe documents (2026-10-10): a PDF / Word / text file's recipes as the AI found them (title, servings, lines,
-- steps, notes, source hints), by the file's sha256. The first preview of a document starts a read job (an import_jobs
-- row with choices_sha 'read'); later previews and the apply use this, so the item keys of the preview and the apply
-- match without a second AI read. Rows older than 14 days are deleted when a new read starts.
CREATE TABLE IF NOT EXISTS document_reads(
  file_sha text PRIMARY KEY,
  format text NOT NULL,
  recipes jsonb NOT NULL,
  warnings jsonb NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL DEFAULT now()
);
