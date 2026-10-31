# Meal Prep on the web: privacy policy

_Published at https://mealprep.drwhom.ca/privacy_

_Last updated: 31 October 2026_

Meal Prep at mealprep.drwhom.ca helps a household plan the week's dinners, keep its recipes and build the grocery
order. It is a private tool: you can only use it if a household invites you. The developer runs the site and the
household server behind it, on a computer in Ontario, Canada. This policy says what that server keeps, who else
sees any of it, and how to get it removed. (The Android app has its own policy, in `docs/play/privacy-policy.md`
in the app's source code.)

## Signing in with Google

You sign in with your Google account. The site asks Google only for your basic profile: your name, your email
address and your profile picture (the `openid`, `email` and `profile` permissions). It never asks for your Gmail,
Google Drive, contacts or anything else in your Google account, and it never sees your Google password.

The server keeps your Google account's ID, your name, email address and profile picture link, and when you last
signed in. Signing in only works if a household has invited you (by an invite link, or by your email address).

## What the household server keeps

Everything below belongs to your household. Other households can't see it.

- **Recipes:** recipes you add or import, with their ingredients, steps, source (a link, a cookbook's title and
  page, or a name you give), photo links, and your ratings and notes.
- **Plans:** which dinners are on which night, prep plans and cook cards.
- **Shopping:** shopping lists, staples, items you add by hand, the fridge list, the products you prefer, and
  grocery carts with their history (what was in each cart, and when it was sent).
- **Your household:** its name, its members and their roles, and invites that haven't been used yet.
- **An audit log** of who sent each cart to the grocery store.
- **AI usage:** each time the server uses its AI service for your household, it records which feature, how big
  the request was, its cost and whether it worked. That keeps each household within a daily budget.
- **Sign-in sessions:** when you sign in, the server keeps a record of the session (when it started, when it was
  last used, when it ends, and your browser's name and version). A session ends after 30 days or when you sign out.

## Children

Children under 13 don't sign in. A parent or other adult in the household can add a child profile, which has a
first name only and no login, so the child can have their own ratings. The site is not offered to children under
13 in any other way. Members 13 and over sign in with their own Google account.

## Who else sees some of it

The server shares data with these services only to do what you ask:

- **The AI service (Anthropic).** When you import a recipe, add a photo of a cookbook page or a fridge shelf,
  import a document, or ask the server to tidy or check a list or cart, it sends that recipe text, photo or
  document (or the list) to Anthropic's API so the AI can read it. Photos and documents are not kept after they
  are read: the server keeps what was read (the recipe text or fridge items), plus a fingerprint of a document so
  the same file isn't read twice. Anthropic handles this under its own commercial terms and privacy policy.
- **PC Express (Loblaws).** Only when someone in your household searches for a product, builds a cart or sends a
  cart, the server asks PC Express for products, prices and stock, and sends the cart's items to PC Express.
  The cart you send becomes your own order on loblaws.ca, under Loblaws' privacy policy. Product details and
  prices are kept on the server and shared between households, because they are about products, not people.
- **NYT Cooking (The New York Times).** When you import an NYT Cooking link, the server fetches that recipe page.
  Recipe photos are links to NYT's image servers, so your browser loads them from there when you look at a recipe
  (the site tells your browser not to send NYT which page you were on).
- **Book catalogues.** When you type a cookbook's title, the server may look it up in Open Library or Google Books
  to suggest the book's full title and author. Only the title you typed is sent.
- **Cloudflare.** The site is served through Cloudflare, which protects it and passes requests on to the server.
  Cloudflare sees each visitor's IP address and the requests made to the site, as any network provider would,
  under its own privacy policy.
- **Google**, when you sign in (above).

No one else gets your data. It is never sold, rented or used for advertising.

## Cookies

The site uses one cookie: a session cookie that keeps you signed in. It is set only after you sign in, can't be
read by the page's scripts, and is sent only to this site. There are no advertising, analytics or tracking
cookies, and no third-party cookies.

## What the site doesn't do

- No advertising, analytics, tracking or crash-reporting services.
- No selling or sharing of data, beyond the services listed above.
- No access to your Google account beyond your name, email address and profile picture.

## Seeing, correcting or deleting your data

Your household's owner can change or remove members, recipes, plans and lists in the site itself. To get a copy
of your data, to have something corrected, or to have your account or your household's data deleted, ask your
household's owner, or email titulartarantula@gmail.com. Deleting your account removes your sign-in, your sessions
and your membership; deleting a household removes everything the household kept. The server's backups may keep a
copy for a while after that, until they are replaced.

## Where the data is kept, and the law

The server is in Ontario, Canada, and the data is stored there. Meal Prep follows Canada's Personal Information
Protection and Electronic Documents Act (PIPEDA). The services listed above may process the data they receive
outside Canada (for example in the United States). The connection to the site is encrypted (HTTPS).

## Changes and contact

If this policy changes, the new version will be posted at this address with a new date.
Questions: titulartarantula@gmail.com.
