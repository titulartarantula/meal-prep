// TanStack Query hooks: one place for query keys and the invalidations that follow each write.
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { ApiError, del, get, patch, post, put, setSession } from "./client";
import type {
  Choices, Draft, ImportReport, ListItem, Me, Permission, PlanEntry, PrepPlanSummary, Recipe, RecipeDetail, RatingIn,
  ShareResult, Staple, WeekSummary,
} from "./types";

export const keys = {
  me: ["me"] as const,
  recipes: ["recipes"] as const,
  recipe: (id: number) => ["recipe", id] as const,
  weeks: ["weeks"] as const,
  week: (w: string) => ["week", w] as const,
  weekDraft: (w: string) => ["weekDraft", w] as const,
  prep: (w: string) => ["prep", w] as const,
  draft: (id: number) => ["draft", id] as const,
  staples: ["staples"] as const,
  defaultWeek: ["defaultWeek"] as const,
};

/** Signed in? 401 = no; 404 = a server from before sign-in (dev against 0.15): treated as the shared token. */
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      try {
        const me = await get<Me>("/auth/me");
        setSession(me.csrf_token, me.household?.id);
        return me;
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          return { via: "legacy", user: null, households: [], household: { id: 0, name: "Home" }, member_id: null,
                   roles: [], permissions: ALL } satisfies Me;
        }
        throw e;
      }
    },
    retry: (n, e) => !(e instanceof ApiError && e.status === 401) && n < 2,
    staleTime: 5 * 60_000,
  });
}

const ALL: Permission[] = ["recipe.read", "plan.read", "rate.self", "rate.for_children", "request.create", "plan.edit",
  "recipe.edit", "fridge.edit", "request.approve", "cart.build", "cart.send", "members.manage", "roles.assign",
  "household.settings"];

export function useCan() {
  const me = useMe().data;
  return (p: Permission) => !!me?.permissions.includes(p);
}

export function useRecipes() {
  return useQuery({ queryKey: keys.recipes, queryFn: () => get<Recipe[]>("/recipes"), staleTime: 60_000 });
}

export function useRecipe(id: number | null) {
  return useQuery({
    queryKey: keys.recipe(id ?? 0),
    queryFn: () => get<RecipeDetail>(`/recipes/${id}`),
    enabled: id != null,
  });
}

export function useWeeks(from: string, count = 6) {
  return useQuery({
    queryKey: [...keys.weeks, from, count],
    queryFn: () => get<WeekSummary[]>(`/weeks?from=${from}&count=${count}`),
  });
}

export function useWeek(week: string) {
  return useQuery({ queryKey: keys.week(week), queryFn: () => get<PlanEntry[]>(`/weeks/${week}`) });
}

/** The newest cart draft for a week, or null when there is none (404). */
export function useWeekDraft(week: string | null) {
  return useQuery({
    queryKey: keys.weekDraft(week ?? ""),
    queryFn: () => orNull(get<Draft>(`/weeks/${week}/draft`)),
    enabled: !!week,
  });
}

export function usePrepPlan(week: string) {
  return useQuery({ queryKey: keys.prep(week), queryFn: () => orNull(get<PrepPlanSummary>(`/weeks/${week}/prep-plan`)) });
}

async function orNull<T>(p: Promise<T>): Promise<T | null> {
  try {
    return await p;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

function invalidateWeek(qc: QueryClient, week: string) {
  qc.invalidateQueries({ queryKey: keys.week(week) });
  qc.invalidateQueries({ queryKey: keys.weeks });
  qc.invalidateQueries({ queryKey: keys.weekDraft(week) });
  qc.invalidateQueries({ queryKey: keys.defaultWeek });
  qc.invalidateQueries({ queryKey: keys.recipes });
}

/** Move an entry to a night (0–6) or back to the tray (null). Optimistic: the card moves at once. */
export function useMoveEntry(week: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, day }: { id: number; day: number | null }) => patch<void>(`/plan/${id}`, { day }),
    onMutate: async ({ id, day }) => {
      await qc.cancelQueries({ queryKey: keys.week(week) });
      const before = qc.getQueryData<PlanEntry[]>(keys.week(week));
      qc.setQueryData<PlanEntry[]>(keys.week(week), (es) => es?.map((e) => (e.id === id ? { ...e, day } : e)));
      return { before };
    },
    onError: (_e, _v, ctx) => ctx?.before && qc.setQueryData(keys.week(week), ctx.before),
    onSettled: () => invalidateWeek(qc, week),
  });
}

export function useRemoveEntry(week: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => del<void>(`/plan/${id}`),
    onSuccess: () => invalidateWeek(qc, week),
  });
}

/** Add a recipe to a week, optionally straight onto a night. */
export function useAddToWeek() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ week, recipeId, day }: { week: string; recipeId: number; day?: number | null }) => {
      const e = await post<PlanEntry>(`/weeks/${week}/entries`, { recipe_id: recipeId });
      if (day != null) await patch<void>(`/plan/${e.id}`, { day });
      return { ...e, day: day ?? e.day };
    },
    onSuccess: (_e, v) => invalidateWeek(qc, v.week),
  });
}

export function useRate(week: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, rating }: { id: number; rating: RatingIn | null }) =>
      rating ? put<void>(`/plan/${id}/rating`, rating) : del<void>(`/plan/${id}/rating`),
    onSuccess: () => {
      invalidateWeek(qc, week);
      qc.invalidateQueries({ queryKey: ["recipe"] });
    },
  });
}

export function useShareImport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => post<ShareResult>("/recipes/share", { text }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.recipes }),
  });
}

/** A file import: dry run (preview, or a 202 read job for documents) or apply (202 job). */
export function importFile(file: File, dryRun: boolean, choices?: Record<string, string>) {
  const f = new FormData();
  f.append("file", file);
  f.append("dry_run", String(dryRun));
  f.append("name", file.name);
  if (choices) f.append("choices", JSON.stringify(choices));
  return post<ImportReport>("/recipes/import", f);
}

export const getImport = (id: number) => get<ImportReport>(`/imports/${id}`);

export function useStaples() {
  return useQuery({ queryKey: keys.staples, queryFn: () => get<Staple[]>("/staples") });
}

export function useDefaultCartWeek() {
  return useQuery({ queryKey: keys.defaultWeek, queryFn: () => get<{ week: string | null }>("/cart/default-week") });
}

export function makeList(weeks: string[], staples: number[]) {
  return post<ListItem[]>("/list", { weeks, people: 4, staples, fridge: false });
}

export function useDraft(id: number) {
  return useQuery({
    queryKey: keys.draft(id),
    queryFn: () => get<Draft>(`/drafts/${id}`),
    refetchInterval: (q) => (q.state.data?.status === "building" ? 1500 : false),
  });
}

export function useCreateDraft() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ items, weeks }: { items: ListItem[]; weeks: string[] }) =>
      post<{ id: number; status: string }>("/drafts", { items, weeks }),
    onSuccess: (_d, v) => v.weeks.forEach((w) => qc.invalidateQueries({ queryKey: keys.weekDraft(w) })),
  });
}

/** Line edits return the whole draft; it replaces the cached one. */
export function useDraftWrite(id: number) {
  const qc = useQueryClient();
  const done = (d: Draft) => qc.setQueryData(keys.draft(id), d);
  const patchLine = useMutation({
    mutationFn: ({ line, ...body }: { line: number; quantity?: number; removed?: boolean; product_code?: string; pinned?: boolean }) =>
      patch<Draft>(`/drafts/${id}/lines/${line}`, body),
    onMutate: async ({ line, ...body }) => {
      await qc.cancelQueries({ queryKey: keys.draft(id) });
      const before = qc.getQueryData<Draft>(keys.draft(id));
      if (before && (body.quantity != null || body.removed != null)) {
        qc.setQueryData<Draft>(keys.draft(id), {
          ...before,
          lines: before.lines.map((l) => (l.id === line ? { ...l, ...body } : l)),
        });
      }
      return { before };
    },
    onError: (_e, _v, ctx) => ctx?.before && qc.setQueryData(keys.draft(id), ctx.before),
    onSuccess: done,
  });
  const addLine = useMutation({
    mutationFn: (b: { term: string; product_code: string; quantity: number }) => post<Draft>(`/drafts/${id}/lines`, b),
    onSuccess: done,
  });
  const send = useMutation({
    mutationFn: () => post<{ pcx_cart_id: string }>(`/drafts/${id}/send`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.draft(id) });
      qc.invalidateQueries({ queryKey: keys.weeks });
      qc.invalidateQueries({ queryKey: ["weekDraft"] });
      qc.invalidateQueries({ queryKey: keys.defaultWeek });
    },
  });
  return { patchLine, addLine, send };
}

export function lineChoices(draft: number, line: number, term: string | null, page = 0) {
  const q = new URLSearchParams({ page: String(page) });
  if (term) q.set("term", term);
  return get<Choices>(`/drafts/${draft}/lines/${line}/choices?${q}`);
}

export function productChoices(term: string, page = 0) {
  return get<Choices>(`/products/choices?${new URLSearchParams({ term, page: String(page) })}`);
}

export function useSignOut() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => post<void>("/auth/logout"),
    onSettled: () => {
      setSession(undefined, null);
      qc.clear();
      location.assign("/");
    },
  });
}

export function useSwitchHousehold() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (household_id: number) => put<void>("/auth/household", { household_id }),
    onSuccess: (_r, id) => {
      setSession(qc.getQueryData<Me>(keys.me)?.csrf_token, id);
      qc.invalidateQueries();
    },
  });
}
