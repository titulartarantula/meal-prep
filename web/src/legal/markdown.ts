// The privacy policy and terms are written in Markdown (docs/web/*.md) and turned into static HTML at build time
// (vite.config.ts), so the pages need no JavaScript. Only what those files use: # and ## headings, paragraphs,
// "- " lists, **bold**, _italic_, `code`, [links](https://…) and bare https:// links. Everything else is escaped.

const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

// A link to this site's own pages stays on the page's origin (so it also works in dev and on the LAN).
const SITE = "https://mealprep.drwhom.ca";
const href = (url: string) => (url.startsWith(SITE + "/") ? url.slice(SITE.length) : url);

export function inline(text: string): string {
  const out: string[] = [];
  // Links and code first, from the raw text, so their contents aren't read as emphasis.
  const re = /\[([^\]]+)\]\((https:\/\/[^\s)]+)\)|`([^`]+)`|(https:\/\/[^\s)]+[^\s).,;:])/g;
  let last = 0;
  for (let m: RegExpExecArray | null; (m = re.exec(text)); last = re.lastIndex) {
    out.push(emphasis(esc(text.slice(last, m.index))));
    if (m[1] !== undefined) out.push(`<a href="${esc(href(m[2]))}">${emphasis(esc(m[1]))}</a>`);
    else if (m[3] !== undefined) out.push(`<code>${esc(m[3])}</code>`);
    else out.push(`<a href="${esc(href(m[4]))}">${esc(m[4])}</a>`);
  }
  out.push(emphasis(esc(text.slice(last))));
  return out.join("");
}

function emphasis(s: string): string {
  return s
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[\s(])_(.+?)_(?=$|[\s).,;:])/g, "$1<em>$2</em>")
    // email_off: Cloudflare would otherwise swap the address for a script-decoded placeholder (unreadable without JS).
    .replace(/(^|[\s(])([\w.+-]+@[\w-]+(?:\.[\w-]+)+\w)/g, '$1<!--email_off--><a href="mailto:$2">$2</a><!--/email_off-->');
}

export interface LegalDoc {
  title: string;
  updated: string;
  html: string;
}

/** The page's title (the # heading), its "Last updated" date and the body as HTML (## headings become <h2>). */
export function legalDoc(md: string): LegalDoc {
  let title = "";
  let updated = "";
  const html: string[] = [];
  let para: string[] = [];
  let list: string[] = [];
  const flush = () => {
    if (para.length) html.push(`<p>${inline(para.join(" "))}</p>`);
    if (list.length) html.push(`<ul>\n${list.map((li) => `<li>${inline(li)}</li>`).join("\n")}\n</ul>`);
    para = [];
    list = [];
  };
  for (const raw of md.split(/\r?\n/)) {
    const line = raw.trim();
    let m: RegExpMatchArray | null;
    if (!line) flush();
    else if ((m = line.match(/^# (.+)$/))) { flush(); title = m[1]; }
    else if ((m = line.match(/^## (.+)$/))) { flush(); html.push(`<h2>${inline(m[1])}</h2>`); }
    else if (/^_Published at .+_$/.test(line)) flush();       // the page itself is that address
    else if ((m = line.match(/^_Last updated: (.+)_$/))) { flush(); updated = m[1]; }
    else if ((m = line.match(/^- (.+)$/))) { if (para.length) flush(); list.push(m[1]); }
    else if (list.length) list[list.length - 1] += " " + line;   // a list item's continuation line
    else para.push(line);
  }
  flush();
  return { title, updated, html: html.join("\n") };
}
