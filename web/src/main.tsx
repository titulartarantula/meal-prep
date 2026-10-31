import "@fontsource-variable/inter/wght.css";
import "./theme/tokens.css";
import "./theme/app.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import { initTheme } from "./lib/theme";
import { setUnauthorizedHandler } from "./api/client";
import { keys } from "./api/queries";

initTheme();
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 15_000, refetchOnWindowFocus: true, retry: 1 } },
});
setUnauthorizedHandler(() => queryClient.invalidateQueries({ queryKey: keys.me }));

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
);
