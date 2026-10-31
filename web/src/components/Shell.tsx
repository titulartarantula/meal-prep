import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useMe, useSignOut, useSwitchHousehold, useWeeks } from "../api/queries";
import { initials } from "../lib/format";
import { weekName, weekOf, weekRange } from "../lib/weeks";
import { Icon } from "./Icon";
import { Menu, MenuItem } from "./ui";
import { useTheme } from "../lib/theme";

export function Shell() {
  const me = useMe().data!;
  const thisWeek = weekOf(new Date());
  const weeks = useWeeks(thisWeek, 4).data ?? [];
  const signOut = useSignOut();
  const switchHousehold = useSwitchHousehold();
  const [theme, setTheme] = useTheme();
  const navigate = useNavigate();
  const name = me.user?.name ?? me.user?.email ?? "Home network";
  const roleText = me.roles.length ? me.roles.filter((r) => r !== "contributor").map(roleName).join(" · ") : "Shared token";

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
              title={`${weekName(w.week)} · ${w.entries} recipe${w.entries === 1 ? "" : "s"}${w.carted ? " · cart sent" : ""}`}>
              <span>{weekRange(w.week)}</span>
              <span className={`dot${w.carted ? " sent" : w.entries ? " planned" : ""}`}
                aria-label={w.carted ? "cart sent" : w.entries ? `${w.entries} planned` : "empty"} />
            </NavLink>
          ))}
        </nav>
        <div className="me">
          <span className="av">{initials(me.user?.name ?? "Home")}</span>
          <div className="me-text"><b>{name}</b><small>{roleText}</small></div>
          <Menu label="Account and settings" icon="gear" align="left">
            {(close) => (
              <>
                {me.households.length > 1 && me.households.map((h) => (
                  <MenuItem key={h.id} icon={h.id === me.household?.id ? "check" : undefined}
                    onSelect={() => { close(); switchHousehold.mutate(h.id); navigate("/week"); }}>{h.name}</MenuItem>
                ))}
                <MenuItem icon={theme === "dark" ? "sun" : "moon"} onSelect={() => { close(); setTheme(theme === "dark" ? "light" : "dark"); }}>
                  {theme === "dark" ? "Light mode" : "Dark mode"}
                </MenuItem>
                {theme !== "system" && <MenuItem icon="gear" onSelect={() => { close(); setTheme("system"); }}>Match the system</MenuItem>}
                {me.via !== "legacy" && <MenuItem icon="logout" onSelect={() => { close(); signOut.mutate(); }}>Sign out</MenuItem>}
              </>
            )}
          </Menu>
        </div>
      </aside>
      <Outlet />
    </div>
  );
}

const ROLE_NAMES: Record<string, string> = { owner: "Owner", approver: "Approver", planner: "Planner", contributor: "Contributor", child: "Child" };
const roleName = (r: string) => ROLE_NAMES[r] ?? r;
