#!/usr/bin/env python3
"""Proof that the two design gates CAN fail (build-check runs this next to the gates themselves).

  check_tokens.py : a planted hex colour / literal font size / colour built in code / named colour (Brushes.White,
                    Fill="Red") is caught; a token reference is not
  check_logo.py   : a planted hand-drawn mark (three tilted rings, TorusGeometry, ring file, unofficial logo image, the
                    official logo copied under a new name, a re-saved lookalike) is caught

and that both are green on the real repo.

Run: python tools/design/test_design_gates.py
"""
import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


tokens = _load("check_tokens", HERE / "check_tokens.py")
logo = _load("check_logo", HERE / "check_logo.py")


def _tree(files):
    d = Path(tempfile.mkdtemp())
    for rel, content in files.items():
        p = d / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            p.write_bytes(content)
        else:
            p.write_text(content, encoding="utf-8")
    return d


class TokenGate(unittest.TestCase):
    def _hits(self, files):
        return tokens.scan(_tree(files))

    def test_clean_files_pass(self):
        files = {
            "UI/Good.xaml": '<TextBlock Foreground="{StaticResource Brush.Text}" FontSize="{StaticResource FontSize.Body}"/>',
            "UI/Good.cs": 'var b = (Brush)FindResource("Brush.Text"); // #FF0000 in a comment is fine\n',
        }
        self.assertEqual(self._hits(files), [])

    def test_planted_hex_in_xaml_is_caught(self):
        hits = self._hits({"UI/Bad.xaml": '<Border Background="#1D2420"/>'})
        self.assertEqual([(h[0], h[2]) for h in hits], [("UI/Bad.xaml", "hex colour #1D2420")])

    def test_planted_hex_in_code_is_caught(self):
        hits = self._hits({"UI/Bad.cs": 'var c = (Color)ColorConverter.ConvertFromString("#80FFFFFF");\n'})
        self.assertTrue(any("hex colour" in h[2] for h in hits))

    def test_planted_font_size_is_caught(self):
        self.assertTrue(any(h[2] == "literal FontSize" for h in self._hits({"UI/Bad.xaml": '<TextBlock FontSize="13"/>'})))
        self.assertTrue(any(h[2] == "literal FontSize" for h in self._hits({"UI/Bad.cs": "t.FontSize = 12;\n"})))

    def test_colour_built_in_code_is_caught(self):
        hits = self._hits({"UI/Bad.cs": "var c = Color.FromRgb(0x62, 0xB5, 0x43);\n"})
        self.assertTrue(any(h[2] == "colour built in code" for h in hits))

    def test_named_colours_in_code_are_caught(self):
        for line in ("var b = Brushes.White;", "c = Colors.Red;", "new FrameworkPropertyMetadata(System.Windows.Media.Brushes.Black)"):
            hits = self._hits({"UI/Bad.cs": line + "\n"})
            self.assertTrue(any(h[2] == "named colour" for h in hits), line)

    def test_named_colours_in_xaml_are_caught(self):
        for attr in ('Fill="White"', 'Foreground="Red"', 'Background="Black"', 'Color="Gold"', 'BorderBrush="Silver"'):
            hits = self._hits({"UI/Bad.xaml": "<Border %s/>" % attr})
            self.assertTrue(any(h[2] == "named colour" for h in hits), attr)

    def test_transparent_and_tokens_are_not_named_colours(self):
        files = {"UI/Good.cs": "var c = Colors.Transparent; var b = Brushes.Transparent;\n",
                 "UI/Good.xaml": '<Border Background="Transparent" Fill="{StaticResource Brush.Text}"/>'}
        self.assertEqual(self._hits(files), [])

    def test_retinting_a_token_is_allowed(self):
        self.assertEqual(self._hits({"UI/Good.cs": "var c = Color.FromArgb(0x26, hue.R, hue.G, hue.B);\n"}), [])

    def test_token_files_may_hold_the_values(self):
        self.assertEqual(self._hits({"Theme/Colors.xaml": '<Color x:Key="Color.Bg">#0E1110</Color>'}), [])

    def test_exit_code_fails_on_a_bypass(self):
        d = _tree({"UI/Bad.xaml": '<Border Background="#FFFFFF"/>'})
        self.assertEqual(tokens.main(["--root", str(d)]), 1)
        self.assertEqual(tokens.main(["--root", str(d), "--report"]), 0)

    def test_real_launcher_has_zero_bypasses(self):
        self.assertEqual(tokens.scan(tokens.DEFAULT_ROOT), [])


class LogoGate(unittest.TestCase):
    OFFICIAL = b"official-logo-bytes"

    def setUp(self):
        import hashlib
        self._saved = set(logo.OFFICIAL_MD5)
        logo.OFFICIAL_MD5.add(hashlib.md5(self.OFFICIAL).hexdigest())

    def tearDown(self):
        logo.OFFICIAL_MD5.clear()
        logo.OFFICIAL_MD5.update(self._saved)

    def _hits(self, files):
        return [rel for rel, _ in logo.scan(_tree(files))]

    def test_clean_tree_passes(self):
        files = {
            "src/App/Brand.xaml": '<Image Source="Assets/Brand/logo.png"/>',
            "src/App/Assets/Brand/logo.png": self.OFFICIAL,
            "website/assets/hero.png": b"anything",
        }
        self.assertEqual(self._hits(files), [])

    def test_three_tilted_xaml_ellipses_are_caught(self):
        xaml = ('<Canvas><Ellipse Width="20" Height="8"><Ellipse.RenderTransform><RotateTransform Angle="60"/>'
                '</Ellipse.RenderTransform></Ellipse><Ellipse><RotateTransform Angle="120"/></Ellipse></Canvas>')
        self.assertEqual(self._hits({"src/App/Mark.xaml": xaml}), ["src/App/Mark.xaml"])

    def test_svg_rotate_60_120_is_caught(self):
        svg = '<svg><ellipse rx="10" ry="4"/><ellipse transform="rotate(60)"/><ellipse transform="rotate(120)"/></svg>'
        self.assertEqual(self._hits({"website/mark.html": svg}), ["website/mark.html"])

    def test_angle_list_is_caught(self):
        self.assertEqual(self._hits({"src/mark.js": "const tilts = [0, 60, 120];"}), ["src/mark.js"])
        self.assertEqual(self._hits({"src/Mark.java": "float[] a = {0f, 60f, 120f};"}), ["src/Mark.java"])

    def test_torus_geometry_is_caught(self):
        self.assertEqual(self._hits({"website/js/scene.js": "new THREE.TorusGeometry(1, 0.02, 8, 64)"}), ["website/js/scene.js"])

    def test_vendor_library_is_not(self):
        self.assertEqual(self._hits({"website/js/vendor/three.min.js": "class TorusGeometry{}"}), [])

    def test_ring_mark_file_names_are_caught(self):
        self.assertEqual(self._hits({"src/App/UI/Controls/OriginMark.xaml": "<UserControl/>"}), ["src/App/UI/Controls/OriginMark.xaml"])
        self.assertEqual(self._hits({"website/ring-1.png": b"x"}), ["website/ring-1.png"])

    def test_ring_backdrop_art_only_survives_in_pulled_modules(self):
        pulled = "src/mods/versions/1.21.5/src/client/resources/assets/originclient/textures/ui/ring-0.png"
        self.assertEqual(self._hits({pulled: b"x", pulled.replace("ring-0.png", "rings.json"): "{}"}), [])
        live = "src/mods/versions/1.21.1/src/client/resources/assets/originclient/textures/ui/ring-0.png"
        self.assertEqual(self._hits({live: b"x"}), [live])
        shared = "src/mods/shared/src/client/resources/assets/originclient/textures/ui/rings.json"
        self.assertEqual(self._hits({shared: "{}"}), [shared])

    def test_unofficial_logo_image_is_caught(self):
        self.assertEqual(self._hits({"src/App/Assets/origin-logo.png": b"a redrawn logo"}), ["src/App/Assets/origin-logo.png"])
        self.assertEqual(self._hits({"src/App/Assets/origin-logo.png": self.OFFICIAL}), [])

    def test_official_logo_copied_under_a_new_name_is_caught(self):
        self.assertEqual(self._hits({"src/App/Assets/brand-1.png": self.OFFICIAL}), ["src/App/Assets/brand-1.png"])
        self.assertEqual(self._hits({"src/App/Assets/icon.png": self.OFFICIAL, "src/App/Assets/origin_logo.png": self.OFFICIAL}), [])

    def test_redrawn_lookalike_under_any_name_is_caught(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow not installed (the lookalike check is skipped without it)")
        import io
        ring = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        from PIL import ImageDraw
        d = ImageDraw.Draw(ring)
        d.ellipse((20, 20, 108, 108), outline=(255, 255, 255, 255), width=6)
        d.ellipse((44, 44, 84, 84), outline=(255, 255, 255, 255), width=6)
        buf = io.BytesIO()
        ring.save(buf, "PNG")
        saved = list(logo._REFERENCE), logo.REFERENCE_LOGO
        logo._REFERENCE.clear()
        ref = Path(tempfile.mkdtemp()) / "ref.png"
        ref.write_bytes(buf.getvalue())
        logo.REFERENCE_LOGO = ref
        try:
            resaved = io.BytesIO()
            ring.resize((200, 200)).save(resaved, "PNG")
            other = io.BytesIO()
            Image.new("RGBA", (128, 128), (200, 30, 30, 255)).save(other, "PNG")
            self.assertEqual(self._hits({"website/img/hero-1.png": resaved.getvalue()}), ["website/img/hero-1.png"])
            self.assertEqual(self._hits({"website/img/banner.png": other.getvalue()}), [])
            self.assertEqual(self._hits({"website/img/favicon-192.png": resaved.getvalue()}), [])
        finally:
            logo._REFERENCE.clear()
            logo._REFERENCE.extend(saved[0])
            logo.REFERENCE_LOGO = saved[1]

    def test_exit_code_fails_on_a_hit(self):
        d = _tree({"src/mark.js": "const tilts = [0, 60, 120];"})
        self.assertEqual(logo.main(["--root", str(d)]), 1)
        self.assertEqual(logo.main(["--root", str(_tree({"a.txt": "hi"}))]), 0)

    def test_real_repo_is_clean(self):
        self.assertEqual(logo.scan(logo.REPO), [])


if __name__ == "__main__":
    unittest.main(verbosity=1)
