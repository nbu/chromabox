<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Chromabox Changelog

## [Unreleased]

## [1.0.0]

### Added

- Color swatches next to colors in any text file, such as Markdown, CSV, JSON, YAML or source code. Click a swatch
  to pick a new color; one undo reverts the whole pick.
- Color formats, each with its own setting under _Settings | Tools | Chromabox | Formats_:
  - Hex `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`; short forms can be turned off.
  - CSS functions `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hwb()`, in legacy comma and modern space syntax.
  - CSS Color 4 `oklch()`, `oklab()`, `lab()`, `lch()` and `color()`; colors outside sRGB get an "out of gamut" mark.
  - Integer colors `0xAARRGGBB`, with an option for `0xRRGGBBAA`.
  - Language constructors: Compose and Flutter `Color(0xFF112233)`, Flutter `Color.fromARGB()`, AWT `new Color()`,
    UIKit, AppKit and SwiftUI `UIColor(red:green:blue:alpha:)`.
  - Named CSS colors like `tomato`, off by default.
  - Float tuples like `[0.1, 0.2, 0.3]`, off by default, optionally only in chosen file types.
- A picked color is written back in the notation it was written in: function, separators, units, number style and
  case. Alpha is added in the same notation when the picked color has it.
- Display modes: swatch only, swatch and color text, gutter icons, off (_View | Chromabox_ or the editor context
  menu). In gutter mode, a line's icon shows up to four colors; click it to pick one.
- Colors the IDE already shows a swatch for, such as AWT colors in Java, don't get a second one. CSS-like file types
  are skipped by default; the list is editable in settings.
