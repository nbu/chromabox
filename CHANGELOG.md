<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Chromabox Changelog

## [Unreleased]

## [1.1.0]

### Added

- More color formats, each with its own setting under _Settings | Tools | Chromabox | Formats_:
  - CSS functions `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hwb()`, in legacy comma and modern space syntax.
  - CSS Color 4 `oklch()`, `oklab()`, `lab()`, `lch()` and `color()`; colors outside sRGB get an "out of gamut" mark.
  - Integer colors `0xAARRGGBB`, with an option for `0xRRGGBBAA`.
  - Language constructors: Compose and Flutter `Color(0xFF112233)`, Flutter `Color.fromARGB()`, AWT `new Color()`,
    UIKit, AppKit and SwiftUI `UIColor(red:green:blue:alpha:)`.
  - Named CSS colors like `tomato`, off by default.
  - Float tuples like `[0.1, 0.2, 0.3]`, off by default, optionally only in chosen file types.
- A picked color is written back in the notation it was written in: function, separators, units, number style and
  case. Alpha is added in the same notation when the picked color has it.

### Changed

- Colors the IDE already shows a swatch for, such as AWT colors in Java, no longer get a second one.
- Swatch only mode keeps a color folded when the caret moves next to it or onto it, including with IdeaVim. Select
  the swatch, or use _Expand_, to edit the text.
- Files are scanned in the background, so typing isn't slowed down.

### Fixed

- Folds left over from an earlier session no longer hide color text.

## [1.0.0]

### Added

- Color swatches for hex colors in any text file, with click-to-pick editing.
- Display modes: swatch only, swatch and hex, off.
- Settings for display mode, short hex forms, and skipped file types.
