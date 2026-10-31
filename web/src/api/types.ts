// Types for the API. Routes the server types (pydantic models) come from the generated schema.d.ts
// (`npm run gen:api` after scripts/dump_openapi.py); the ones that return plain dicts are written out here,
// following the server code and the Android DTOs (android/.../data/api/Dtos.kt).
import type { components } from "./schema";

type S = components["schemas"];
export type Recipe = S["RecipeOut"] & { id: number };
export type RecipeDetail = S["RecipeDetail"] & { id: number };
export type Ingredient = S["Ingredient"];
export type RatingSummary = S["RatingSummary"];
export type PlanEntry = S["PlanEntry"];
export type Rating = S["Rating"];
export type ListItem = S["ListItem"];
export type Staple = S["Staple"];
export type RatingIn = S["RatingIn"];

export type Permission =
  | "recipe.read" | "plan.read" | "rate.self" | "rate.for_children" | "request.create" | "plan.edit" | "recipe.edit"
  | "fridge.edit" | "request.approve" | "cart.build" | "cart.send" | "members.manage" | "roles.assign"
  | "household.settings";

export interface Household {
  id: number;
  name: string;
  store_id?: number;
  plan?: string;
  status?: string;
}

/** GET /auth/me. `via` legacy = the home network's shared token (dev only; no user). */
export interface Me {
  via: "cookie" | "bearer" | "legacy";
  user: { id: number; email: string; name: string | null; picture: string | null } | null;
  households: { id: number; name: string; member_id: number }[];
  household: Household | null;
  member_id: number | null;
  roles: string[];
  permissions: Permission[];
  csrf_token?: string;
}

export interface WeekSummary {
  week: string;
  entries: number;
  carted: boolean;
  cart_stale: boolean;
}

export interface Product {
  code: string;
  name: string;
  brand?: string | null;
  package_size?: string | null;
  price?: number | null;
  stock?: string | null;
  sold_by?: string | null;
  image?: string | null;
  unit_price?: number | null;
  unit_price_unit?: string | null;
  regular_price?: number | null;
  was_price?: number | null;
  deal_text?: string | null;
  sponsored?: boolean;
  weighted?: boolean;
  price_quantity?: number | null;
  min_qty?: number | null;
  max_qty?: number | null;
  interval?: number | null;
  link?: string | null;
}

export interface DraftLine {
  id: number;
  item_key: string;
  name: string;
  qty: number | null;
  unit: string | null;
  prep?: string | null;
  recipes: string[];
  product: Product | null;
  quantity: number | null;
  source: "memory" | "ai" | "user" | "none" | string;
  alternatives: Product[];
  removed: boolean;
  status: string;
  packs_min?: number | null;
  needs_check?: boolean;
  why?: string | null;
  staple?: boolean;
  staple_packs?: number | null;
  pinned?: boolean;
  note?: string | null;
  extra_id?: number | null;
}

export interface Draft {
  id: number;
  status: "building" | "ready" | "failed" | "sent";
  error: string | null;
  progress: { done: number; total: number };
  weeks: string[];
  lines: DraftLine[];
  pcx_cart_id: string | null;
  estimated_total: number;
  created_at: string | null;
  sent_at: string | null;
  stale: boolean;
}

export interface Choice {
  product: Product;
  group: "current" | "bought" | "deal" | "other" | "out" | string;
  current: boolean;
  pinned: boolean;
  last_bought?: { date?: string | null; price?: number | null; quantity?: number | null } | null;
  price_change?: number | null;
  suggested_quantity: number;
  cover?: { packs: number; cost: number; size: string } | null;
  bulk?: boolean;
}

export interface Choices {
  term: string;
  page: number;
  total: number;
  has_more: boolean;
  choices: Choice[];
}

export interface ShareResult {
  recipe: Recipe;
  entry: PlanEntry | null;
  existing: boolean;
}

export interface ImportItem {
  key: string;
  index: number;
  title: string | null;
  status: string;
  action: "add" | "skip" | "update" | string;
  reasons: string[];
  recipe_id?: number | null;
  match?: { recipe_id: number | null; title: string; by: string } | null;
}

/** POST /recipes/import (preview, or 202 job) and GET /imports/{id}. */
export interface ImportReport {
  dry_run: boolean;
  format?: string | null;
  warnings: string[];
  counts: Record<string, number>;
  items: ImportItem[];
  id?: number;
  status?: "running" | "done" | "failed";
  progress?: { done: number; total: number };
  error?: string | null;
  existing?: boolean;
}

export interface PrepPlanSummary {
  id: number;
  status: string;
  checklist?: { done: number; total: number };
  stale?: boolean;
}

/** Grams as "300 g" or "1.25 kg". */
export function grams(g: number): string {
  return g >= 1000 ? `${String(Math.round(g / 10) / 100)} kg` : `${g} g`;
}

/** Stepper limits for a product, as the Android chooser: packs, or grams by the product's interval when weighted. */
export function qtyRule(p: Product | null | undefined) {
  const step = Math.max(1, p?.interval ?? 1);
  return { step, min: Math.max(1, p?.min_qty ?? 1), max: p?.max_qty && p.max_qty > 0 ? p.max_qty : p?.weighted ? 20000 : 99,
           format: (q: number) => (p?.weighted ? grams(q) : String(q)) };
}

/** What a product line costs: price is per price_quantity grams for weighted products. */
export function lineCost(l: Pick<DraftLine, "product" | "quantity" | "removed">): number {
  const p = l.product;
  if (!p || p.price == null || !l.quantity || l.removed) return 0;
  return (p.price * l.quantity) / (p.price_quantity ?? 1);
}
