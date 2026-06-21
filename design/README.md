# App icon — masters

The "Signaler" app launcher icon. Source of truth is
**`semaphore-translator-icon.svg`** (vector); every raster below is generated
from it. Re-export from the SVG any time the art changes.

The per-platform assets wired into the apps live alongside the platform
sources, not here:

- **iOS** — `ios/Sources/Assets.xcassets/AppIcon.appiconset/` (Light + Dark +
  Tinted, iOS 18 appearances).
- **Android** — `android/app/src/main/res/mipmap-*/` (legacy `ic_launcher.png`
  + adaptive `ic_launcher_foreground.png`), `mipmap-anydpi-v26/ic_launcher.xml`,
  and the `ic_launcher_background` color in `res/values/colors.xml`.

This `design/` folder keeps the **masters** so the whole set stays
reproducible from vector and doesn't only live in a Downloads folder.

## Color tokens
| Token            | Hex       | Use                                  |
|------------------|-----------|--------------------------------------|
| Background navy  | `#182A47` | icon background / adaptive bg color  |
| Flag red         | `#E23B2E` | flag, cap band                       |
| Flag yellow      | `#F7C600` | flag (hoist half, toward the hands)  |
| Figure / cap     | `#FFFFFF` | body + cap                           |
| Skin             | `#F4E4C8` | head + arms                          |

## Masters in this folder
```
semaphore-translator-icon.svg             Master, full-bleed square WITH navy background.
semaphore-translator-icon-foreground.svg  Figure + flags only, transparent (Android adaptive foreground).
semaphore-translator-icon-background.svg  Solid navy square (Android adaptive background).
semaphore-translator-icon-dark.svg        iOS 18 Dark variant (transparent; system supplies dark bg).
semaphore-translator-icon-tinted.svg      iOS 18 Tinted variant (grayscale, transparent).
semaphore-translator-icon-1024.png        1024px PNG master (App Store / Play listing render source).
```

## iOS 18 appearances (Light / Dark / Tinted)
The catalog's `Contents.json` declares all three appearances, each a single
1024 image:
- **Light** — `icon-1024.png`, full color on navy (opaque).
- **Dark** — `icon-1024-dark.png`, art on **transparent**; iOS draws its own
  dark background.
- **Tinted** — `icon-1024-tinted.png`, **grayscale** on transparent; iOS
  applies the user's tint by luminance (whites take the most tint). The flag
  diagonal and figure stay legible under tint.

The art is full-bleed and square on purpose — iOS applies the rounded-corner
mask itself; do **not** pre-round.

## Android (adaptive icon, API 26+)
The foreground art sits inside the adaptive safe zone, so it won't clip under
any mask. For a true `VectorDrawable`, import
`semaphore-translator-icon-foreground.svg` via Android Studio's **Asset Studio
→ Image Asset** (or any SVG→VectorDrawable converter).

## Regenerating PNGs from the SVG
Any SVG rasterizer works, e.g. with `rsvg-convert`:
```
rsvg-convert -w 1024 -h 1024 semaphore-translator-icon.svg \
  -o semaphore-translator-icon-1024.png
```
