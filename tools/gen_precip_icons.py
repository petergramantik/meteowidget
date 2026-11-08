#!/usr/bin/env python3
"""
Generuje vlastní ikony srážek (velké kapky / vločky podle intenzity).
Mrak a slunce vychází z Meteocons (MIT, Bas Milius).

Výstup:
  app/src/main/assets/meteocons/<nazev>.svg     animované (pro TV)
  app/src/main/res/drawable-nodpi/mc_<nazev>.png statické (pro widget)

Spuštění (z kořene repozitáře): python3 tools/gen_precip_icons.py
Potřebuje rsvg-convert (librsvg).
"""
import os
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets/meteocons")
DRAW = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")

CLOUD = ("M46.5 31.5h-.32a10.49 10.49 0 00-19.11-8 7 7 0 00-10.57 6 7.21 7.21 0 00.1 1.14"
         "A7.5 7.5 0 0018 45.5a4.19 4.19 0 00.5 0v0h28a7 7 0 000-14z")
SUN_RAYS = ("M32 15.71V9.5m0 45v-6.21m11.52-27.81l4.39-4.39M16.09 47.91l4.39-4.39"
            "m0-23l-4.39-4.39m31.82 31.78l-4.39-4.39M15.71 32H9.5m45 0h-6.21")

DEFS = """<defs>
<linearGradient id="cl" x1="22.56" x2="39.2" y1="21.96" y2="50.8" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#f3f7fe"/><stop offset=".45" stop-color="#f3f7fe"/><stop offset="1" stop-color="#deeafb"/></linearGradient>
<linearGradient id="cd" x1="22.56" x2="39.2" y1="21.96" y2="50.8" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#b9bfc9"/><stop offset=".45" stop-color="#a7aeb9"/><stop offset="1" stop-color="#7d8592"/></linearGradient>
<linearGradient id="su" x1="26.75" x2="37.25" y1="22.91" y2="41.09" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#fbbf24"/><stop offset=".45" stop-color="#fbbf24"/><stop offset="1" stop-color="#f59e0b"/></linearGradient>
<linearGradient id="dr" x1="0" x2="0" y1="0" y2="1">
<stop offset="0" stop-color="#8cbcff"/><stop offset="1" stop-color="#2e7bf0"/></linearGradient>
</defs>"""


def sun(animated):
    anim = ('<animateTransform attributeName="transform" dur="45s" repeatCount="indefinite" '
            'type="rotate" values="0 32 32; 360 32 32"/>') if animated else ""
    return ('<g transform="translate(3 1) scale(.56)">'
            '<circle cx="32" cy="32" r="10.5" fill="url(#su)" stroke="#f8af18" stroke-width=".5"/>'
            f'<path fill="none" stroke="#fbbf24" stroke-linecap="round" stroke-width="3" d="{SUN_RAYS}">{anim}</path>'
            '</g>')


def cloud(dark, animated):
    anim = ('<animateTransform attributeName="transform" additive="sum" dur="7s" repeatCount="indefinite" '
            'type="translate" values="-1.5 0; 1.5 0; -1.5 0"/>') if animated else ""
    fill = "url(#cd)" if dark else "url(#cl)"
    stroke = "#9aa1ad" if dark else "#e6effc"
    return (f'<path fill="{fill}" stroke="{stroke}" stroke-width=".5" transform="translate(0 -7)" d="{CLOUD}">'
            f'{anim}</path>')


def falling(inner, x, y, i, animated, dur, dist):
    """Obalí kapku/vločku animací pádu (posunuté začátky, aby nepadaly naráz)."""
    if not animated:
        return f'<g transform="translate({x} {y})">{inner}</g>'
    begin = f"-{(i * dur / 3.1):.2f}s"
    return (f'<g transform="translate({x} {y})"><g>'
            f'<animateTransform attributeName="transform" type="translate" dur="{dur}s" begin="{begin}" '
            f'repeatCount="indefinite" values="0 -{dist / 2}; 0 {dist / 2}"/>'
            f'<animate attributeName="opacity" dur="{dur}s" begin="{begin}" repeatCount="indefinite" values="0;1;1;0"/>'
            f'{inner}</g></g>')


DROP = ('<path fill="url(#dr)" stroke="#2f6fd8" stroke-width=".4" '
        'd="M0 -5.2C2.6 -1.6 4 .6 4 2.4A4 4 0 0 1 -4 2.4C-4 .6 -2.6 -1.6 0 -5.2Z"/>')
FLAKE = ('<g stroke="#8cc4ff" stroke-width="1.8" stroke-linecap="round">'
         '<path d="M0 -5V5"/><path d="M-4.33 -2.5L4.33 2.5"/><path d="M-4.33 2.5L4.33 -2.5"/></g>'
         '<circle r="1.4" fill="#d6ebff"/>')

POS = {
    1: [(32, 49)],
    2: [(26, 48), (38, 50)],
    3: [(22, 48), (32, 51), (42, 48)],
    4: [(19, 47), (28, 51), (37, 47), (46, 51)],
}


def icon(name, n, kind="rain", dark=True, with_sun=False, mixed=False):
    for animated in (True, False):
        parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">{DEFS}']
        if with_sun:
            parts.append(sun(animated))
        parts.append(cloud(dark, animated))
        for i, (x, y) in enumerate(POS[n]):
            if mixed:
                inner = FLAKE if i % 2 else DROP
            else:
                inner = DROP if kind == "rain" else FLAKE
            if kind == "snow" or (mixed and i % 2):
                parts.append(falling(f'<g>{inner}'
                                     + ('<animateTransform attributeName="transform" type="rotate" dur="6s" '
                                        'repeatCount="indefinite" values="0;360"/>' if animated else '')
                                     + '</g>', x, y, i, animated, 2.4, 6))
            else:
                parts.append(falling(inner, x, y, i, animated, 1.1, 8))
        parts.append("</svg>")
        svg = "".join(parts)
        if animated:
            with open(os.path.join(ASSETS, name + ".svg"), "w") as f:
                f.write(svg)
        else:
            tmp = os.path.join("/tmp", name + "_static.svg")
            with open(tmp, "w") as f:
                f.write(svg)
            png = os.path.join(DRAW, "mc_" + name.replace("-", "_") + ".png")
            subprocess.run(["rsvg-convert", "-w", "128", "-h", "128", tmp, "-o", png], check=True)


if __name__ == "__main__":
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(DRAW, exist_ok=True)
    icon("rain-1", 1, dark=False)
    icon("rain-2", 2)
    icon("rain-3", 3)
    icon("rain-4", 4)
    icon("showers-1", 1, dark=False, with_sun=True)
    icon("showers-2", 2, dark=True, with_sun=True)
    icon("snow-1", 1, kind="snow", dark=False)
    icon("snow-2", 2, kind="snow")
    icon("snow-3", 3, kind="snow")
    icon("snow-showers", 2, kind="snow", dark=False, with_sun=True)
    icon("sleet-2", 2, mixed=True)
    print("hotovo")
