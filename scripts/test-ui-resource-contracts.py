#!/usr/bin/env python3
"""Keep participant UI resources on semantic styles and accessible theme tokens."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class UiResourceContracts(unittest.TestCase):
    def test_house_text_and_buttons_use_font_resources(self):
        for style in ET.parse(ROOT / "res/values/styles.xml").iter("style"):
            if style.get("name", "").startswith("Chronicle.") and style.get("parent", "").startswith(("TextAppearance.", "Widget.Material3.Button")):
                font = style.find("item[@name='fontFamily']")
                self.assertIsNotNone(font, style.get("name"))
                self.assertTrue(font.text.startswith("@font/"), style.get("name"))

    def test_layout_dimensions_and_type_use_tokens(self):
        problems = []
        for path in (ROOT / "res/layout").glob("*.xml"):
            for node in ET.parse(path).iter():
                for key, value in node.attrib.items():
                    if key.startswith((ANDROID, "{http://schemas.android.com/apk/res-auto}")) and re.fullmatch(r"\d+(?:\.\d+)?(?:dp|sp)", value) and value not in {"0dp", "1dp"}:
                        problems.append(f"{path.name} {key.split('}')[1]}={value}")
        self.assertEqual([], problems)

    def test_visual_headings_have_heading_semantics(self):
        problems = []
        for path in (ROOT / "res/layout").glob("*.xml"):
            for node in ET.parse(path).iter("TextView"):
                appearance = node.get("style", "") + node.get(ANDROID + "textAppearance", "")
                if re.search(r"(?:Headline|Title|MetricValue)", appearance):
                    if node.get(ANDROID + "accessibilityHeading") != "true":
                        problems.append(f"{path.name} {node.get(ANDROID + 'id')}")
        self.assertEqual([], problems)

    def test_material_three_components_are_consistent(self):
        forbidden = re.compile(r"MaterialComponents|SwitchMaterial|androidx\.appcompat\.app\.AlertDialog")
        problems = [str(path.relative_to(ROOT)) for path in ROOT.rglob("*")
                    if path.suffix in {".kt", ".xml"} and forbidden.search(path.read_text())]
        self.assertEqual([], problems)

    def test_dark_primary_text_has_normal_text_contrast(self):
        def colors(folder):
            return {node.get("name"): node.text for node in ET.parse(ROOT / f"res/{folder}/colors.xml").iter("color")}
        tokens = colors("values") | colors("values-night")
        def resolve(name):
            value = tokens[name]
            return resolve(value.removeprefix("@color/")) if value.startswith("@color/") else value
        def luminance(name):
            value = resolve(name).lstrip("#")
            channels = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
            return sum(weight * (v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4)
                       for weight, v in zip((.2126, .7152, .0722), channels))
        for background in ("eq_bg", "eq_surface"):
            light, dark = sorted((luminance("colorPrimary"), luminance(background)), reverse=True)
            self.assertGreaterEqual((light + .05) / (dark + .05), 4.5, background)

    def test_expanded_content_is_centered_and_bounded(self):
        styles = ET.parse(ROOT / "res/values-w840dp/styles.xml")
        content = styles.find("style[@name='Chronicle.Content']")
        self.assertIsNotNone(content)
        items = {item.get("name"): item.text for item in content}
        self.assertEqual("@dimen/chronicle_content_max_width", items["android:layout_width"])
        for name in ("activity_enrollment", "fragment_overview", "fragment_uploads", "fragment_settings_home", "fragment_data_sharing"):
            root = ET.parse(ROOT / f"res/layout/{name}.xml").getroot()
            self.assertEqual("@style/Chronicle.Content", root[0].get("style"), name)


if __name__ == "__main__":
    unittest.main()
