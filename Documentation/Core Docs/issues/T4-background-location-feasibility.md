## Question

Does modern Android's background-location model support the continuous, screen-off
GPS tracking Luna needs (long sessions) and the live tracking Crater will eventually
need for speed-based warnings, without requiring privileged access an indie developer
can't realistically get?

## Resolution

For a foreground service to access location while the app isn't visible, Android
requires the `ACCESS_BACKGROUND_LOCATION` runtime permission plus a declared
`location`-type foreground service (mandatory foreground service type declarations
since API 34). This is exactly Prototype 1's existing plan (build guide §10.4) — no
new architecture is needed for Luna; it's a permission + manifest concern, not a
design-seam concern.

The real cost is downstream, at Crater/Artemis: Google Play Console reviews apps using
background-location foreground services against a "minimum scope" policy — justifying
that the feature genuinely needs background access, not just foreground/one-time
access — as part of app review before a public listing goes live. That's a publishing
readiness item for Crater/Artemis's own design phase, not something Luna needs to solve
now, but worth flagging on that future map on day one so it isn't a last-minute
surprise.

Type: research (AFK) — resolved
Blocked by: none
