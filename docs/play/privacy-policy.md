# Meal Prep for Android: privacy policy

_Published at https://github.com/titulartarantula/meal-prep/blob/main/docs/play/privacy-policy.md_

_Last updated: 5 October 2026_

Meal Prep helps a household plan the week's dinners. You share recipe links into the app, it sends them to a
home server **that you set up and choose**, and it shows you the plan from that server. The developer runs no
servers for the app and receives none of your data.

## What the app handles, and where it goes

**Recipe links you share.** When you share an NYT Cooking link to the app, the link and the week you pick are
sent to the server address you enter in Settings. The server reads the recipe and stores it, along with your
week plan (which recipe is on which night, and the scale you choose).

**The saved copy on your phone.** To work offline, the app keeps a copy of the current week's plan on the
phone, in the app's private storage. Uninstalling the app removes it.

**Settings.** The server address and the access token you enter, and your notification preferences, are
stored in the app's private storage on the phone. Android's backup is turned off for the app.

**The server.** The server belongs to you or your household. It stores recipes and plans, and it may send
recipe text to an AI service it is configured to use, in order to read the recipe. That happens on the
server, under the choices of whoever runs it, not in the app.

## What the app does not do

- No advertising, analytics, tracking or crash-reporting services.
- No account with the developer, and no data sent to the developer.
- No selling or sharing of data with anyone. Data goes only to the server you configure.
- No access to your contacts, location, photos, microphone or camera.

## Security

The app talks to your server over the connection you set up. Plain HTTP is allowed only to the household's
own home-network and VPN addresses, because a home server on a private network usually has no HTTPS
certificate. Everything else uses HTTPS. Use a private network such as a VPN whenever the connection
leaves a network you trust.

## Permissions

| Permission | Why |
| --- | --- |
| Network | Talking to your home server |
| Notifications | Telling you when a shared recipe has been added |
| Foreground service (data sync) | Finishing a recipe import in the background after you share a link |

## Children

The app isn't directed at children.

## Changes and contact

If this policy changes, the new version will be posted at this address with a new date.
Questions: titulartarantula@gmail.com.
