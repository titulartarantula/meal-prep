import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { ApiError } from "./api/client";
import { useMe } from "./api/queries";
import { Shell } from "./components/Shell";
import { ErrorNote, Loading, ToastHost } from "./components/ui";
import { SignIn } from "./routes/SignIn";
import { ThisWeek } from "./routes/ThisWeek";
import { Recipes } from "./routes/Recipes";
import { Shopping } from "./routes/Shopping";
import { Cart } from "./routes/Cart";

export function App() {
  const me = useMe();
  if (me.isPending) return <div className="center-page"><Loading /></div>;
  if (me.error instanceof ApiError && me.error.status === 401) return <SignIn />;
  if (me.error) return <div className="center-page"><ErrorNote error={me.error} onRetry={() => me.refetch()} /></div>;
  if (!me.data.household) return <SignIn noHousehold />;
  return (
    <ToastHost>
      <BrowserRouter>
        <Routes>
          <Route element={<Shell />}>
            <Route path="/week/:week?" element={<ThisWeek />} />
            <Route path="/recipes/:id?" element={<Recipes />} />
            <Route path="/shopping" element={<Shopping />} />
            <Route path="/cart/:id" element={<Cart />} />
            <Route path="*" element={<Navigate to="/week" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ToastHost>
  );
}
