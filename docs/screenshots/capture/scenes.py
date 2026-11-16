#!/usr/bin/env python3
"""
Takes the screenshots in docs/screenshots from the demo project (docs/screenshots/demo).

    ./gradlew runIdeForScreenshots        # in another terminal; wait for the IDE window
    python3 docs/screenshots/capture/scenes.py [name ...]

With names, takes only those shots. The IDE is driven through the Robot Server plugin, so leave it alone meanwhile.
"""
import sys
import time

from robot import js, shot, wait_for_ide, wait_until_smart

WIDTH, HEIGHT = 1280, 800


def editor():
    shot("editor", 'open("tokens/colors.yaml"); caret(25, 1); tw("Chromabox").show(); setScope("PROJECT"); clearSelection()', 4)


def contrast():
    shot("contrast",
         'tw("Chromabox").hide(); open("tokens/colors.yaml"); carets([[11, 18], [12, 18]]); action("Chromabox.CheckContrast")',
         cleanup='closePopups(); caret(25, 1)')


def merge():
    js('open("tokens/colors.yaml"); caret(25, 1); tw("Chromabox").show(); setScope("PROJECT")')
    # The palette refreshes after the scope changes; behind the dialog it would be half drawn.
    time.sleep(6)
    shot("merge", 'showMergeLater("#2563eb")', cleanup="closeDialogs()")


def markdown():
    shot("markdown-preview", 'tw("Chromabox").hide(); closeAll(); open("docs/palette.md"); splitPreview(); caret(18, 1)', 6)


def gutter():
    shot("gutter", 'tw("Chromabox").hide(); closeAll(); open("src/theme.ts"); caret(13, 1); action("Chromabox.Mode.Gutter")', 3,
         cleanup='action("Chromabox.Mode.BoxWithHex")')


def color_vision():
    shot("color-vision",
         'tw("Chromabox").show(); setScope("CURRENT_FILE"); closeAll(); open("tokens/colors.yaml"); caret(25, 1); '
         'clearSelection(); action("Chromabox.Simulate.Deuteranopia")', 4,
         cleanup='action("Chromabox.Simulate.None")')


def settings():
    js('tw("Chromabox").hide(); showSettingsLater("Chromabox")')
    time.sleep(3)
    shot("settings", "fitDialog(1100, 740)", 1.5, cleanup="closeDialogs()")


SCENES = {"editor": editor, "contrast": contrast, "merge": merge, "markdown": markdown, "gutter": gutter,
          "color-vision": color_vision, "settings": settings}

if __name__ == "__main__":
    wait_for_ide()
    js("setUp(%d, %d)" % (WIDTH, HEIGHT))
    wait_until_smart()
    for name in sys.argv[1:] or SCENES:
        SCENES[name]()
