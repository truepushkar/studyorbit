# StudyOrbit design system

Native Android, Jetpack Compose, Material 3 underneath. Operate mode: the student is in a task, so consistency beats expression. All tokens live in `OrbitDesign.kt` and `res/values*/palette.xml`; screens never hand-pick these values.

## Scale
- **Spacing** `Gap.xs 4 · sm 8 · md 12 · lg 16 · xl 24`. Screen edge is `ScreenPadding` (24). Page and list rhythm is `Gap.md`; more space above a heading than below it.
- **Radius** `CardShape 20` (cards, art panels) · `ButtonShape 16` · `FieldShape 12` (fields, pickers, icon tiles, segmented tracks) · `SheetShape 28` · pills and circles for chips, day cells, icon buttons. Nested radii are the outer radius minus the padding.
- **Type** (Inter) 13 caption/meta · 14 control and secondary · 16 body and row title · 18 section · 24 sheet · 32 page. Use `OrbitHeading(level = Page | Sheet | Section)` or `SectionTitle`; never `fontSize` a heading. Numerals that change use `tnum`.
- **Touch** 48dp minimum for every target; buttons are 52dp, on-art buttons 48dp.

## Color
Role tokens only (`Paper`, `Ink`, `Muted`, `Accent`, `AccentSurface`, `OrbitSurface`, `Night`, `Line`, `Forest`, `Distracting`, nav tokens). Every one has a light and a dark value. Artwork overlays use the fixed `Art*` tokens in both themes. No raw hex in screens.

## Components (one of each)
| Need | Use |
|---|---|
| Card / tappable row / note | `Panel(tone = Surface \| Tinted \| Quiet, onClick?)` |
| Button | `OrbitButton` (primary, tonal, `container = OrbitSurface` for outlined, `danger`) |
| Button over artwork | `ArtButton` + `ArtLabel` |
| Choose one of few | `OrbitSegmented` |
| Text input, any length | `OrbitField` (`lines`, `helper`, `enabled`) |
| Picker that opens something | `Modifier.orbitField()` row |
| Quick action chip | `OrbitChip` |
| Icon button | `OrbitIconButton` |
| Full-screen page | `OrbitScreen(title, onClose)` (title leading, one close button trailing) |
| Confirmation or small task | `OrbitSheet`; system `AlertDialog` is themed to match |
| Group heading | `SectionTitle(title, caption, action)` |

## Rules
- Every `OrbitSurface` container has the 1dp `Line` border.
- One exit per full-screen page: the close button. No duplicate Cancel under it.
- Ranges (1–180 min) are helper text under a field, not part of its label or suffix.
- Glyphs are not icons: no ↗ → ✦. Use drawables (`ic_external`, `ic_next`).
- Accent is for actions, selection and state, not decoration.
