import { useEffect, useRef, useState } from "react";
import { Icon } from "./Icon";
import { Button } from "./ui";

/** The bookmarklet (installed once): on loblaws.ca it asks for the cart ID, stores it where loblaws.ca keeps its
 *  anonymous cart (as the Android handoff does: DESIGN "Spike results", option a), and reloads. */
export const BOOKMARKLET = "javascript:(()=>{if(!/(^|\\.)loblaws\\.ca$/.test(location.hostname)){alert('Open loblaws.ca first, then click this bookmark.');return}" +
  "var id=(prompt('Paste the Meal Prep cart ID')||'').trim();if(!id)return;if(!/^[0-9a-f-]{36}$/i.test(id)){alert('That is not a Meal Prep cart ID.');return}" +
  "localStorage.setItem('ANONYMOUS_CART_ID',id);localStorage.setItem('lcl-cart-id-banner',id);sessionStorage.removeItem('lcl-grocery-data-cart');" +
  "document.cookie='lcl-cart-id-banner='+id+'; domain=.loblaws.ca; path=/; secure';location.reload()})()";

export function Handoff({ cartId }: { cartId: string }) {
  const link = useRef<HTMLAnchorElement>(null);
  const [copied, setCopied] = useState(false);
  // React refuses javascript: URLs in href, so the bookmarklet's link is set on the element directly.
  useEffect(() => { link.current?.setAttribute("href", BOOKMARKLET); }, []);
  const copy = async () => {
    try { await navigator.clipboard.writeText(cartId); setCopied(true); setTimeout(() => setCopied(false), 2500); } catch { /* shown below */ }
  };
  return (
    <div className="handoff">
      <h2>Load it on loblaws.ca</h2>
      <p className="fine">Your cart is ready at Loblaws. Bring it into your PC id account like this:</p>
      <ol className="handoff-steps">
        <li><i>1</i><div><b>Copy the cart ID</b>
          <div className="cart-id"><code>{cartId}</code><Button size="sm" kind="outline" icon={copied ? "check" : "copy"} onClick={copy}>{copied ? "Copied" : "Copy"}</Button></div></div></li>
        <li><i>2</i><div><b>Open loblaws.ca in a private window</b><small>Ctrl + Shift + N in Chrome or Edge. You must start signed out.</small></div></li>
        <li><i>3</i><div><b>Click “Load Meal Prep cart” in your bookmarks</b><small>Paste the ID when it asks. The page reloads with your cart.</small></div></li>
        <li><i>4</i><div><b>Sign in with PC id</b><small>The cart moves into your account. Pick a pickup time and check out.</small></div></li>
      </ol>
      <div className="bookmarklet-box">
        <span className="fine">First time? Drag this to your bookmarks bar:</span>
        <a ref={link} className="bookmarklet" onClick={(e) => e.preventDefault()} draggable title="Drag me to the bookmarks bar">
          <Icon name="leaf" size={15} />Load Meal Prep cart
        </a>
      </div>
    </div>
  );
}
