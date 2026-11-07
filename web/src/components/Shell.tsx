import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useMe, useSignOut, useSwitchHousehold, useWeeks } from "../api/queries";
import { initials, weekWhat } from "../lib/format";
import type { WeekSummary } from "../api/types";
import { weekName, weekOf, weekRange } from "../lib/weeks";
import { Icon } from "./Icon";
import { Menu, MenuItem } from "./ui";
import { useTheme } from "../lib/theme";
import { useCompact } from "../lib/media";

export function Shell() {
  const me = useMe().data!;
  const thisWeek = weekOf(new Date());
  const weeks = useWeeks(thisWeek, 4).data ?? [];
  const signOut = useSignOut();
  const switchHousehold = useSwitchHousehold();
  const [theme, setTheme, themeChoice] = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const name = me.user?.name ?? me.user?.email ?? "Home network";
  const roleText = me.roles.length ? me.roles.filter((r) => r !== "contributor").map(roleName).join(" · ") : "Shared token";

  const compact = useCompact();

  const account = (align: "left" | "right") => (
    <Menu label="Account and settings" icon="gear" align={align}>
      {(close) => (
        <>
          {compact && <div className="menu-label">{name}{me.household ? ` · ${me.household.name}` : ""}</div>}
          {me.households.length > 1 && me.households.map((h) => (
            <MenuItem key={h.id} icon={h.id === me.household?.id ? "check" : undefined}
              onSelect={() => { close(); switchHousehold.mutate(h.id); navigate("/week"); }}>{h.name}</MenuItem>
          ))}
          <MenuItem icon={theme === "dark" ? "sun" : "moon"} onSelect={() => { close(); setTheme(theme === "dark" ? "light" : "dark"); }}>
            {theme === "dark" ? "Light mode" : "Dark mode"}
          </MenuItem>
          {themeChoice !== "system" && <MenuItem icon="gear" onSelect={() => { close(); setTheme("system"); }}>Match the system</MenuItem>}
          {me.via !== "legacy" && <MenuItem icon="logout" onSelect={() => { close(); signOut.mutate(); }}>Sign out</MenuItem>}
        </>
      )}
    </Menu>
  );

  // Phones and portrait tablets: a slim top bar (brand, account menu) and a bottom tab bar like the Android app's.
  if (compact) {
    return (
      <div className="app compact">
        <a className="skip" href="#main">Skip to content</a>
        <header className="appbar">
          <span className="brand-mark"><Icon name="leaf" size={16} /></span>
          <b>Meal Prep</b>
          {me.household && <small>{me.household.name}</small>}
          <span className="spacer" />
          {account("right")}
        </header>
        <Outlet />
        <nav className="tabbar" aria-label="Main">
          <NavLink to="/week"><span className="tab-ic"><Icon name="week" size={22} /></span><span>This week</span></NavLink>
          <NavLink to="/recipes"><span className="tab-ic"><Icon name="book" size={22} /></span><span>Recipes</span></NavLink>
          <NavLink to="/shopping" className={({ isActive }) => isActive || /^\/cart\//.test(location.pathname) ? "active" : ""}>
            <span className="tab-ic"><Icon name="bag" size={22} /></span><span>Shopping</span>
          </NavLink>
        </nav>
      </div>
    );
  }

  return (
    <div className="app">
      <a className="skip" href="#main">Skip to content</a>
      <aside className="side" aria-label="Main">
        <div className="brand">
          <span className="brand-mark"><Icon name="leaf" size={18} /></span>
          <div><b>Meal Prep</b><small>{me.household?.name ?? ""}</small></div>
        </div>
        <nav className="nav">
          <NavLink to="/week"><Icon name="week" /><span>This week</span></NavLink>
          <NavLink to="/recipes"><Icon name="book" /><span>Recipes</span></NavLink>
          <NavLink to="/shopping"><Icon name="bag" /><span>Shopping</span></NavLink>
        </nav>
        <div className="side-sec" id="weeks-h">Weeks</div>
        <nav className="nav weeklist" aria-labelledby="weeks-h">
          {weeks.map((w) => (
            <NavLink key={w.week} to={w.week === thisWeek ? "/week" : `/week/${w.week}`} end
              title={`${weekName(w.week)} · ${weekWhat(w.entries, w.extras ?? 0)}${w.carted ? " · cart sent" : ""}`}>
              <span>{weekRange(w.week)}</span>
              <span className={`dot${w.carted ? " sent" : planned(w) ? " planned" : ""}`}
                aria-label={w.carted ? "cart sent" : w.entries ? `${w.entries} planned` : planned(w) ? "planned" : "empty"} />
            </NavLink>
          ))}
        </nav>
        <div className="me">
          <span className="av">{initials(me.user?.name ?? "Home")}</span>
          <div className="me-text"><b>{name}</b><small>{roleText}</small></div>
          {account("left")}
        </div>
      </aside>
      <Outlet />
    </div>
  );
}

const ROLE_NAMES: Record<string, string> = { owner: "Owner", approver: "Approver", planner: "Planner", contributor: "Contributor", child: "Child" };
const roleName = (r: string) => ROLE_NAMES[r] ?? r;

/** A week counts as planned with a recipe, a store item or a named night. */
const planned = (w: WeekSummary) => w.entries > 0 || (w.extras ?? 0) > 0 || (w.named_nights ?? 0) > 0;
