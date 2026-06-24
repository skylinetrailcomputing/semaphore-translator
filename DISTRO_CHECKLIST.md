# semaphore-translator — distribution checklist

Pre-distribution gate. Items below must be resolved (or explicitly
waived in this file, with reason) before this app ships beyond your
own devices.

Companion docs:
- `~/claude-workspace-2026/knowledge/legal-entity-for-apps.md` —
  entity strategy, EULA mechanics, E&O thresholds,
  attorney-consult triggers. Authoritative for the "why."
- This project's `README.md` — distribution scope and current
  status.

> **6b-8 pre-pass (2026-06-24):** items independently verifiable as
> done — or waivable with a recorded reason — are resolved below ahead
> of the live go/no-go, to shrink the final review to genuinely
> store-dependent items. The bottom Sign-off, reviewed "at the moment
> of TestFlight expansion," remains the true gate; it and the
> store-record items stay open until #83 / #84 / #85 land.

## Entity & legal posture

- [x] LLC publisher of record confirmed — Apple Developer + Play
  Console accounts are both under Skyline Trail Computing LLC
  (D-U-N-S on file).
- [x] EULA reviewed (Apple Standard EULA + any project-specific
  addendum) — custom Tier-A EULA authored, hosted, and
  maintainer-reviewed (#80); both apps link to it
- [x] E&O / Tech professional liability bound *if* this app has a
  user-injury surface (health, finance, safety, dietary). See
  `legal-entity-for-apps.md` §4-5 to decide.
  **WAIVED** — no user-injury surface (Tier A); E&O is bound under
  the Skyline umbrella regardless.
- [x] Per-app attorney consult complete *if* this app warrants
  per-project legal review (see `legal-entity-for-apps.md` §7
  for triggers)
  **WAIVED** — closed F&F, maintainer-is-legal best-effort; real
  counsel deferred to pre-wide-release (stays a hard gate).

## App Store / Play Store paperwork

- [x] Privacy Policy authored and hosted (required by App Store
  even for zero-data apps) — hosted on GitHub Pages (#79); states the
  on-device / no-storage / no-transmission posture
- [ ] App Store Connect "App Privacy" questionnaire answered
      *(hold: answered in the ASC record — #83)*
- [x] Apple Small Business Program enrolled if any paid tier
  (drops commission 30% → 15%; Skyline qualifies)
  **WAIVED** — free app, no paid tier, no IAP/donations (Skyline is
  enrolled regardless).
- [ ] Listing copy reviewed against marketing-copy guardrails
  below
      *(hold: listing copy authored during store setup — #83/#84)*

## In-app surfaces

- [x] First-launch disclaimer screen *if* the app makes any
  claim users could rely on (verdicts, recommendations, scores)
  — satisfied **electively** (#87, `e008b68`): a version-refreshing,
  education/entertainment-only click-through gates Home on both
  platforms. Not *warranted* (no reliance claim); exceeds Tier-A min.
- [x] Persistent "About / Disclaimers" link in app settings
  — shipped both platforms (#82): Settings → About, with EULA +
  Privacy Policy links (iOS `AboutView`, Android `AboutScreen`).
- [x] Verdict / rating UX honest about uncertainty — don't
  collapse to a binary when the underlying data isn't binary
  — satisfied by design: no verdict/score surface; a low-confidence
  read shows `·` (and a framing hint) rather than forcing a letter.

## Marketing copy guardrails

Never use, anywhere (App Store listing, marketing site, in-app
copy, screenshots, social):
- "guaranteed", "always", "100%"
- "safe for [allergy / condition]"
- Health, medical, or dietary-advice claims unless the app is
  actually registered as a medical device

## App-specific risk bright-lines

**WAIVED — no meaningful risk surface (Tier A** per
`~/claude-workspace-2026/knowledge/closed-beta-fast-path.md`**).**

- **No money:** free, no IAP, no donations.
- **No PII of consequence:** camera frames are processed on-device per
  frame and discarded — nothing stored or transmitted. No accounts, no
  cloud, no telemetry, no network for core function.
- **No injury/reliance surface:** the worst a user can believe is a
  mis-decoded semaphore letter, which carries zero safety, health,
  financial, or dietary consequence. This is the structural opposite of
  the Veganalysis allergen surface that drove its heavy legal track.

The one real obligation that remains is **camera-privacy honesty**, not
risk mitigation: a specific camera purpose string and a short privacy
note stating the on-device/no-storage/no-transmission posture (tracked
under "App Store / Play Store paperwork" above). No first-launch
disclaimer-reliance screen is warranted (the app makes no claim a user
relies on); a plain "About" note suffices.

**Update (6b-9, #87):** although not *warranted*, an
education/entertainment-only first-launch disclaimer gate was added
electively — a version-refreshing click-through shown before the Home
fork on both platforms, distilled from the EULA. This *exceeds* the
Tier-A minimum. **(6b-8 pre-pass, 2026-06-24:** the "First-launch
disclaimer screen" box above is now checked — the gate is built and
merged; the final Sign-off review below remains the true gate.)

## Sign-off

- [ ] All items above either checked or explicitly waived (with
  reason recorded inline)
      *(near-complete after the 6b-8 pre-pass; gated only on the
      store-record items above — #83/#84)*
- [ ] This file reviewed at the moment of TestFlight expansion
  / public listing
      *(THE go/no-go — flip both Sign-off boxes when #83/#84/#85 land)*
