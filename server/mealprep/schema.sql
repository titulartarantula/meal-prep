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
      IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()
                 AND table_name = 'staples' AND column_name = 'household_id') THEN   -- since households: the first one
        EXECUTE 'INSERT INTO staples(household_id, name, qty, position) SELECT (SELECT min(id) FROM households), n, 1, p '
                'FROM (VALUES (''2% milk'', 0), (''eggs'', 1), (''lemonade'', 2)) v(n, p)';
      ELSE
        INSERT INTO staples(name, qty, position) VALUES ('2% milk', 1, 0), ('eggs', 1, 1), ('lemonade', 1, 2);
      END IF;
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

-- Ratings from the recipe itself (2026-10-31, web "Rate" on a recipe card): a time cooked that wasn't a planned night
-- (or whose night was never placed). One per recipe and day; rating it again that day overwrites. A night on the plan
-- for the recipe on that day takes the rating instead (PUT /recipes/{id}/rating), so the two never double up. They
-- count in the summary like plan ratings; rating_history logs them with plan_id NULL + recipe_rating_id.
CREATE TABLE IF NOT EXISTS recipe_ratings(
  id bigserial PRIMARY KEY,
  recipe_id int NOT NULL REFERENCES recipes ON DELETE CASCADE,
  cooked_on date NOT NULL,
  family smallint NOT NULL CHECK (family BETWEEN 1 AND 5),
  company text CHECK (company IN ('yes','maybe','no')),
  note text,
  rated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS recipe_ratings_recipe_idx ON recipe_ratings(recipe_id);
ALTER TABLE rating_history ALTER COLUMN plan_id DROP NOT NULL;
ALTER TABLE rating_history ADD COLUMN IF NOT EXISTS recipe_rating_id bigint;

-- Good for company (2026-10-31): one household verdict per recipe ("would we make it for guests?"), set by an Owner
-- (permission recipe.company), not a per-person rating answer. No row = not decided yet. set_by = the member (NULL =
-- carried over from the old rating answers or an import: source 'migrated' / 'import'). The company columns of
-- ratings / recipe_ratings / imported_ratings stay as history but are no longer read for the verdict.
CREATE TABLE IF NOT EXISTS recipe_company(
  recipe_id int PRIMARY KEY REFERENCES recipes ON DELETE CASCADE,
  verdict text NOT NULL CHECK (verdict IN ('yes','no')),
  set_by int,
  set_at timestamptz NOT NULL DEFAULT now(),
  source text NOT NULL DEFAULT 'member' CHECK (source IN ('member','migrated','import'))
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

-- Product chooser (0.12.0): the latest full product snapshot from a search (photos, description, offers, order
-- limits; price and stock stay in price_observations), "Always buy this" pins on a remembered pick (a pin follows
-- its product: picking another product for the key unpins), and a line's note (why the usual product wasn't used).
ALTER TABLE products ADD COLUMN IF NOT EXISTS detail jsonb;
ALTER TABLE picks ADD COLUMN IF NOT EXISTS pinned boolean NOT NULL DEFAULT false;
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS note text;

-- Items added by search (0.13.0): a product chosen in the chooser for a week, outside the recipes and staples. Drafts
-- of the week get a line for each (cart_lines.extra_id); see extras.py.
CREATE TABLE IF NOT EXISTS extras(
  id serial PRIMARY KEY,
  week date NOT NULL,
  term text NOT NULL,
  key text NOT NULL,
  product_code text NOT NULL,
  quantity int NOT NULL CHECK (quantity > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS extras_week_idx ON extras(week);
ALTER TABLE cart_lines ADD COLUMN IF NOT EXISTS extra_id int;

-- Fridge cleaner (0.14.0): the household's perishables on hand. `key` = the shopping-list name the item matches
-- (staples.name_key: the cleaned, singular name without the unit family), set once when the name is added or edited
-- (AI mapping onto the household's vocabulary, else the rule). An item leaves only when someone removes it.
-- amount_as_of = the day the amount was last set or confirmed. qty null = "some" (no count); unit null = a count.
CREATE TABLE IF NOT EXISTS fridge_items(
  id serial PRIMARY KEY,
  name text NOT NULL,
  key text NOT NULL,
  key_source text NOT NULL CHECK (key_source IN ('ai','rule','list')),
  qty numeric,
  unit text,
  qty_estimated boolean NOT NULL DEFAULT false,
  amount_as_of date NOT NULL DEFAULT CURRENT_DATE,
  use_soon boolean NOT NULL DEFAULT false,
  use_by date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS fridge_items_key_unit_idx ON fridge_items(key, coalesce(unit, ''));

-- Fridge photos (0.14.0): a read of 1–6 photos proposes changes to the fridge list; nothing is saved until the
-- review is applied (result = what the apply did). The photos are deleted as soon as the read ends: only the
-- proposals are kept. photos_sha = sha256 over the photos in order, so a resend of the same photos returns that read.
CREATE TABLE IF NOT EXISTS fridge_reads(
  id serial PRIMARY KEY,
  photos_sha text NOT NULL,
  photo_count int NOT NULL,
  status text NOT NULL DEFAULT 'reading' CHECK (status IN ('reading','ready','failed','applied','dismissed')),
  proposals jsonb NOT NULL DEFAULT '[]',
  result jsonb,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  applied_at timestamptz
);
CREATE INDEX IF NOT EXISTS fridge_reads_sha_idx ON fridge_reads(photos_sha);

-- Morning-after fridge nudge (0.14.0): one answer per plan entry (applied or dismissed), so a tap applies once, even
-- from both phones at the same time. changes = what the apply took off the list (for the app's message).
CREATE TABLE IF NOT EXISTS fridge_nudges(
  plan_id int PRIMARY KEY REFERENCES plan(id) ON DELETE CASCADE,
  action text NOT NULL CHECK (action IN ('applied','dismissed')),
  changes jsonb NOT NULL DEFAULT '[]',
  answered_at timestamptz NOT NULL DEFAULT now()
);

-- What one pack holds when its size text doesn't say (0.15.0): Loblaws lists Green Onion as "1 ea", and that "ea" is
-- a bunch of about 6. Learned by the cart's quantity check (source 'check') or set by the household on a cart line
-- ('user', which a check never overwrites). Keyed by product code; the planner reads it from Product.pieces.
CREATE TABLE IF NOT EXISTS pack_contents(
  code text PRIMARY KEY,
  pieces int NOT NULL CHECK (pieces BETWEEN 1 AND 100),
  word text NOT NULL,
  source text NOT NULL CHECK (source IN ('check','user')),
  reason text,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Web + households (2026-10-30, server tasks 1–2). Everything a household owns carries household_id, and Postgres
-- row-level security keeps each household's rows to itself: a request runs as the role mealprep_app with
-- mealprep.household_id set (db.scope), so every existing query sees only that household and an insert takes the
-- household from the column default. The login role (superuser/owner) bypasses RLS: schema migrations, the startup
-- clean-up of interrupted jobs, backups. Shared caches stay global: products, price_observations, pack_contents,
-- document_reads (and seeds). Identity tables (users, sessions, members, roles, invites …) are not household-filtered:
-- the code reads them with explicit household ids.
CREATE TABLE IF NOT EXISTS users(
  id serial PRIMARY KEY,
  google_sub text NOT NULL UNIQUE,
  email text,
  email_verified boolean NOT NULL DEFAULT false,
  name text,
  picture text,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_login_at timestamptz
);
CREATE TABLE IF NOT EXISTS households(
  id serial PRIMARY KEY,
  name text NOT NULL,
  store_id text NOT NULL DEFAULT '1092',
  plan text NOT NULL DEFAULT 'free',
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','closed')),
  ai_daily_budget int CHECK (ai_daily_budget >= 0),      -- AI calls per rolling 24 h; null = no limit
  created_by int REFERENCES users,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS members(
  id serial PRIMARY KEY,
  household_id int NOT NULL REFERENCES households ON DELETE CASCADE,
  user_id int REFERENCES users,
  display_name text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('account','child')),
  birth_year int CHECK (birth_year BETWEEN 1900 AND 2200),
  email text,                                            -- an unlinked account is claimed by this (verified) address
  joined_at timestamptz NOT NULL DEFAULT now(),
  CHECK (kind = 'account' OR (user_id IS NULL AND email IS NULL)),
  UNIQUE(household_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS members_email_idx ON members(household_id, lower(email)) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS members_user_idx ON members(user_id);
-- The member the shared (legacy) bearer token acts as; null = the household's first Owner.
ALTER TABLE households ADD COLUMN IF NOT EXISTS legacy_member_id int REFERENCES members ON DELETE SET NULL;
CREATE TABLE IF NOT EXISTS roles(
  id serial PRIMARY KEY,
  key text NOT NULL UNIQUE,
  name text NOT NULL,
  for_kind text NOT NULL CHECK (for_kind IN ('account','child'))   -- who can hold it (child = only, exclusive)
);
CREATE TABLE IF NOT EXISTS role_permissions(
  role_id int NOT NULL REFERENCES roles ON DELETE CASCADE,
  permission text NOT NULL,
  PRIMARY KEY(role_id, permission)
);
CREATE TABLE IF NOT EXISTS member_roles(
  member_id int NOT NULL REFERENCES members ON DELETE CASCADE,
  role_id int NOT NULL REFERENCES roles,
  PRIMARY KEY(member_id, role_id)
);
-- Roles and their permissions are data (a new role needs no code); these are the starting set. The seed only adds
-- what is missing, so a permission removed by hand stays removed until its seed name changes.
INSERT INTO roles(key, name, for_kind) VALUES ('owner','Owner / Admin','account'),
  ('approver','Approver / Purchaser','account'), ('planner','Planner','account'),
  ('contributor','Contributor','account'), ('child','Child','child') ON CONFLICT (key) DO NOTHING;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM seeds WHERE name = 'role_permissions-1') THEN
    INSERT INTO role_permissions(role_id, permission)
      SELECT r.id, p FROM roles r, LATERAL unnest(CASE r.key
        WHEN 'owner' THEN ARRAY['members.manage','roles.assign','household.settings']
        WHEN 'approver' THEN ARRAY['request.approve','cart.build','cart.send']
        WHEN 'planner' THEN ARRAY['plan.edit','recipe.edit','fridge.edit','rate.for_children']
        WHEN 'contributor' THEN ARRAY['recipe.read','plan.read','rate.self','request.create']
        ELSE ARRAY[]::text[] END) p
      ON CONFLICT DO NOTHING;
    INSERT INTO seeds(name) VALUES ('role_permissions-1');
  END IF;
END $$;
-- role_permissions-2 (2026-10-31): Owners decide "Good for company" for the household (recipe.company).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM seeds WHERE name = 'role_permissions-2') THEN
    INSERT INTO role_permissions(role_id, permission)
      SELECT id, 'recipe.company' FROM roles WHERE key = 'owner' ON CONFLICT DO NOTHING;
    INSERT INTO seeds(name) VALUES ('role_permissions-2');
  END IF;
END $$;
-- Child is a child profile's only role and only a child profile's (owner/approver minimums are checked in code).
CREATE OR REPLACE FUNCTION member_roles_kind_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE mkind text; rkind text;
BEGIN
  SELECT kind INTO mkind FROM members WHERE id = NEW.member_id;
  SELECT for_kind INTO rkind FROM roles WHERE id = NEW.role_id;
  IF mkind IS DISTINCT FROM rkind THEN
    RAISE EXCEPTION 'role % can''t be held by a % member', NEW.role_id, mkind USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS member_roles_kind ON member_roles;
CREATE TRIGGER member_roles_kind BEFORE INSERT OR UPDATE ON member_roles
  FOR EACH ROW EXECUTE FUNCTION member_roles_kind_check();

CREATE TABLE IF NOT EXISTS invites(
  id serial PRIMARY KEY,
  household_id int NOT NULL REFERENCES households ON DELETE CASCADE,
  email text,                                            -- null = a link anyone signed in can use once
  roles text[] NOT NULL DEFAULT '{contributor}',
  member_id int REFERENCES members ON DELETE CASCADE,    -- claims this unlinked member (a seeded account, a child turning 13)
  token_hash text NOT NULL UNIQUE,
  created_by int REFERENCES members ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_by int REFERENCES users,
  used_at timestamptz,
  revoked_at timestamptz
);
CREATE TABLE IF NOT EXISTS sessions(
  id_hash text PRIMARY KEY,                              -- sha256 of the token; the token itself is never stored
  user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
  household_id int REFERENCES households ON DELETE SET NULL,   -- the household picked last
  kind text NOT NULL CHECK (kind IN ('web','app')),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  user_agent text
);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions(user_id);
-- An OAuth login in progress (GET /auth/login → Google → GET /auth/callback): single use, 10 minutes.
CREATE TABLE IF NOT EXISTS login_states(
  state_hash text PRIMARY KEY,
  code_verifier text NOT NULL,
  nonce text NOT NULL,
  next text,
  invite_hash text,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
-- The Android app signs in through the same round trip: its login state names the app (the return URL is built from
-- an allow-list, never taken from the request) and carries the S256 challenge of a verifier only the app holds.
ALTER TABLE login_states ADD COLUMN IF NOT EXISTS app_id text;
ALTER TABLE login_states ADD COLUMN IF NOT EXISTS app_challenge text;
-- A finished app sign-in waiting to be picked up by POST /auth/app-token: single use, 2 minutes, stored hashed.
CREATE TABLE IF NOT EXISTS app_codes(
  code_hash text PRIMARY KEY,
  user_id int NOT NULL REFERENCES users ON DELETE CASCADE,
  household_id int REFERENCES households ON DELETE SET NULL,
  app_challenge text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log(
  id bigserial PRIMARY KEY,
  household_id int REFERENCES households ON DELETE CASCADE,
  member_id int,
  user_id int,
  action text NOT NULL,
  target text,
  detail jsonb,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_log_household_idx ON audit_log(household_id, at);
-- One row per AI call (metering for a later paid plan): household, feature, tokens when the provider reports them.
CREATE TABLE IF NOT EXISTS ai_usage(
  id bigserial PRIMARY KEY,
  household_id int REFERENCES households ON DELETE CASCADE,
  member_id int,
  feature text NOT NULL,
  provider text,
  model text,
  input_tokens int,
  output_tokens int,
  cost_usd numeric(10,4),
  ok boolean NOT NULL,
  seconds real,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ai_usage_household_idx ON ai_usage(household_id, at);

-- The first household: every row from before households belongs to it (renamed by the seed CLI, mealprep.seed).
INSERT INTO households(name, ai_daily_budget) SELECT 'Household', NULL WHERE NOT EXISTS (SELECT 1 FROM households);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mealprep_app') THEN
    CREATE ROLE mealprep_app NOLOGIN;
  END IF;
  IF NOT pg_has_role(current_user, 'mealprep_app', 'MEMBER') THEN
    EXECUTE format('GRANT mealprep_app TO %I', current_user);
  END IF;
END $$;

DO $$
DECLARE
  t text;
  first_id int := (SELECT min(id) FROM households);
  owned text[] := ARRAY['recipes','plan','picks','pick_history','carts','cart_weeks','cart_lines','ratings',
                        'rating_history','prep_plans','prep_tasks','prep_task_events','cook_cards','staples',
                        'imported_ratings','import_jobs','extras','fridge_items','fridge_reads','fridge_nudges',
                        'recipe_ratings','recipe_company'];
BEGIN
  FOREACH t IN ARRAY owned LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()
                   AND table_name = t AND column_name = 'household_id') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN household_id int REFERENCES households', t);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = t
               AND column_name = 'household_id' AND is_nullable = 'YES') THEN
      EXECUTE format('UPDATE %I SET household_id = %s WHERE household_id IS NULL', t, first_id);
      EXECUTE format('ALTER TABLE %I ALTER COLUMN household_id SET NOT NULL', t);
      EXECUTE format('ALTER TABLE %I ALTER COLUMN household_id SET DEFAULT '
                     'current_setting(''mealprep.household_id'')::int', t);
      EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I(household_id)', t || '_household_idx', t);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = t AND policyname = 'household') THEN
      EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
      EXECUTE format('CREATE POLICY household ON %I USING (household_id = '
                     'NULLIF(current_setting(''mealprep.household_id'', true), '''')::int) '
                     'WITH CHECK (household_id = NULLIF(current_setting(''mealprep.household_id'', true), '''')::int)', t);
    END IF;
  END LOOP;
END $$;

-- Per-household uniques (they were global with one household).
ALTER TABLE recipes DROP CONSTRAINT IF EXISTS recipes_source_url_key;
CREATE UNIQUE INDEX IF NOT EXISTS recipes_household_source_url_idx ON recipes(household_id, source_url);
DROP INDEX IF EXISTS recipes_uid_idx;
CREATE UNIQUE INDEX IF NOT EXISTS recipes_household_uid_idx ON recipes(household_id, uid);
DROP INDEX IF EXISTS fridge_items_key_unit_idx;
DROP INDEX IF EXISTS recipe_ratings_household_recipe_day_idx;   -- one per recipe and day → per person (below)
CREATE UNIQUE INDEX IF NOT EXISTS fridge_items_household_key_unit_idx ON fridge_items(household_id, key, coalesce(unit, ''));
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'picks_pkey'
             AND pg_get_constraintdef(oid) = 'PRIMARY KEY (key)') THEN
    ALTER TABLE picks DROP CONSTRAINT picks_pkey;
    ALTER TABLE picks ADD PRIMARY KEY (household_id, key);
  END IF;
END $$;

-- Per-person ratings (2026-10-31): each member rates a time cooked for themselves (a parent can rate for a child
-- profile: member_id = the child, entered_by = the parent). member_id NULL = "Family": every rating from before
-- profiles, and what the shared token (the phones) writes. No FK to members: a removed member's ratings stay (shown
-- as a former member). One rating per time cooked and person: ratings (plan_id, member_id) and recipe_ratings
-- (household, recipe, day, member_id), NULLS NOT DISTINCT so there's one Family rating too.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()
                 AND table_name = 'ratings' AND column_name = 'id') THEN
    ALTER TABLE ratings ADD COLUMN id bigserial;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ratings_pkey'
             AND pg_get_constraintdef(oid) = 'PRIMARY KEY (plan_id)') THEN
    ALTER TABLE ratings DROP CONSTRAINT ratings_pkey;
    ALTER TABLE ratings ADD PRIMARY KEY (id);
  END IF;
END $$;
ALTER TABLE ratings ADD COLUMN IF NOT EXISTS member_id int;
ALTER TABLE ratings ADD COLUMN IF NOT EXISTS entered_by int;
CREATE UNIQUE INDEX IF NOT EXISTS ratings_plan_member_idx ON ratings(plan_id, member_id) NULLS NOT DISTINCT;
ALTER TABLE recipe_ratings ADD COLUMN IF NOT EXISTS member_id int;
ALTER TABLE recipe_ratings ADD COLUMN IF NOT EXISTS entered_by int;
CREATE UNIQUE INDEX IF NOT EXISTS recipe_ratings_household_recipe_day_member_idx
  ON recipe_ratings(household_id, recipe_id, cooked_on, member_id) NULLS NOT DISTINCT;
ALTER TABLE rating_history ADD COLUMN IF NOT EXISTS member_id int;
ALTER TABLE rating_history ADD COLUMN IF NOT EXISTS entered_by int;

-- Good for company is yes / no (2026-10-31): a "maybe" verdict becomes not decided (no row), each one logged in
-- audit_log (action recipe.company, detail from maybe → null, reason yes-no-only); then the check allows yes / no only.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'recipe_company_verdict_check'
             AND pg_get_constraintdef(oid) LIKE '%maybe%') THEN
    INSERT INTO audit_log(household_id, action, target, detail)
      SELECT household_id, 'recipe.company', 'recipe:' || recipe_id,
             jsonb_build_object('from', 'maybe', 'to', NULL, 'reason', 'yes-no-only', 'set_by', set_by,
                                'set_at', set_at, 'source', source)
      FROM recipe_company WHERE verdict = 'maybe';
    DELETE FROM recipe_company WHERE verdict = 'maybe';
    ALTER TABLE recipe_company DROP CONSTRAINT recipe_company_verdict_check;
    ALTER TABLE recipe_company ADD CONSTRAINT recipe_company_verdict_check CHECK (verdict IN ('yes','no'));
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO mealprep_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mealprep_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO mealprep_app;
