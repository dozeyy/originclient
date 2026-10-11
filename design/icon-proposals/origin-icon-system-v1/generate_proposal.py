#!/usr/bin/env python3
"""Generate the external Origin icon-system proposal.

This folder is deliberately outside every launcher/game resource directory.
Nothing generated here is consumed by Origin until the set is approved.
"""

from __future__ import annotations

import html
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SVG_DIR = ROOT / "svg"

STROKE = 'fill="none" stroke="currentColor" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round"'


def icon(icon_id: str, label: str, category: str, body: str, use: str):
    return {"id": icon_id, "label": label, "category": category, "body": body.strip(), "use": use}


ICONS = [
    # Identity — current atomic idea, redrawn on the same 24 px discipline as the UI family.
    icon("brand.origin-mark", "Origin Mark", "IDENTITY", """
      <ellipse cx="12" cy="12" rx="9" ry="3.8"/>
      <ellipse cx="12" cy="12" rx="9" ry="3.8" transform="rotate(60 12 12)"/>
      <ellipse cx="12" cy="12" rx="9" ry="3.8" transform="rotate(-60 12 12)"/>
      <circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none"/>
    """, "Brand mark / in-game identity"),
    icon("brand.app", "Origin App", "IDENTITY", """
      <rect x="2.5" y="2.5" width="19" height="19" rx="5"/>
      <ellipse cx="12" cy="12" rx="6.1" ry="2.55"/>
      <ellipse cx="12" cy="12" rx="6.1" ry="2.55" transform="rotate(60 12 12)"/>
      <ellipse cx="12" cy="12" rx="6.1" ry="2.55" transform="rotate(-60 12 12)"/>
      <circle cx="12" cy="12" r="1.15" fill="currentColor" stroke="none"/>
    """, "Windows app icon glyph"),

    # Launcher shell.
    icon("launcher.home", "Home", "LAUNCHER", """
      <path d="M3.5 11.2 12 4l8.5 7.2"/>
      <path d="M5.8 10.2v9.3h12.4v-9.3"/>
      <path d="M9.5 19.5v-5.7h5v5.7"/>
    """, "Main navigation"),
    icon("launcher.mods", "Mods", "LAUNCHER + TITLE", """
      <rect x="3" y="3" width="7.5" height="7.5" rx="2"/>
      <rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/>
      <rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/>
      <path d="M17.25 13.5v7.5M13.5 17.25H21"/>
    """, "Launcher nav / title Mods"),
    icon("launcher.settings", "Settings / Options", "LAUNCHER + TITLE", """
      <path d="m9.6 3.4.6-1.4h3.6l.6 1.4 1.6.7 1.4-.6L20 6.1l-.6 1.4.7 1.6 1.4.6v3.6l-1.4.6-.7 1.6.6 1.4-2.6 2.6-1.4-.6-1.6.7-.6 1.4h-3.6l-.6-1.4-1.6-.7-1.4.6L4 16.9l.6-1.4-.7-1.6-1.4-.6V9.7l1.4-.6.7-1.6L4 6.1l2.6-2.6 1.4.6Z"/>
      <circle cx="12" cy="11.5" r="3.2"/>
    """, "Launcher settings / title Options"),
    icon("launcher.account", "Account", "LAUNCHER", """
      <circle cx="12" cy="8" r="3.6"/>
      <path d="M4.8 20c.55-4.2 3.25-6.4 7.2-6.4s6.65 2.2 7.2 6.4"/>
    """, "Account switcher"),
    icon("launcher.download", "Download Update", "LAUNCHER", """
      <path d="M12 3.2v11.1"/>
      <path d="m7.8 10.3 4.2 4.2 4.2-4.2"/>
      <path d="M4 17.3v2.4h16v-2.4"/>
    """, "Launcher update"),
    icon("launcher.folder", "Open Folder", "LAUNCHER", """
      <path d="M3 6.5h6.5l2.2 2.3H21v10.7H3Z"/>
      <path d="M3 9h18"/>
    """, "Mods folder / browse"),
    icon("launcher.trash", "Remove", "LAUNCHER", """
      <path d="M4 6.5h16M9 6.5V4h6v2.5M6.2 6.5l.9 13h9.8l.9-13M10 10v6M14 10v6"/>
    """, "Remove external mod"),
    icon("launcher.back", "Back", "LAUNCHER", """
      <path d="m10 5-7 7 7 7"/>
      <path d="M3.5 12H21"/>
    """, "Version detail back"),
    icon("launcher.minimize", "Minimize", "WINDOW", """
      <path d="M5 17h14"/>
    """, "Window control"),
    icon("launcher.close", "Close", "WINDOW + CLIENT", """
      <path d="m5.5 5.5 13 13M18.5 5.5l-13 13"/>
    """, "Window / overlay close"),
    icon("launcher.loading", "Loading", "LAUNCHER", """
      <path d="M10 16H5v5M14 8h5V3"/>
      <path d="M4.6 9a8 8 0 0 1 14.3-1M19.4 15a8 8 0 0 1-14.3 1"/>
    """, "Animated launch state"),

    # New title actions. Mods and Options reuse the master symbols above.
    icon("title.singleplayer", "Singleplayer", "TITLE", """
      <circle cx="12" cy="8" r="3"/>
      <path d="M6.5 19.5c.45-4.1 2.5-6.2 5.5-6.2s5.05 2.1 5.5 6.2"/>
    """, "Minecraft title action"),
    icon("title.multiplayer", "Multiplayer", "TITLE", """
      <circle cx="9" cy="8.3" r="3"/>
      <path d="M3.6 18.8c.45-3.7 2.45-5.7 5.4-5.7s4.95 2 5.4 5.7"/>
      <circle cx="17.2" cy="9.4" r="2.35"/>
      <path d="M15.1 14.6c.7-.45 1.4-.65 2.15-.65 2.15 0 3.55 1.55 3.85 4.25"/>
    """, "Minecraft title action"),
    icon("title.realms", "Realms", "TITLE", """
      <circle cx="11" cy="12" r="7.7"/>
      <path d="M3.8 9.2h14.4M3.8 14.8h14.4M11 4.3c2.05 2.15 3.1 4.7 3.1 7.7s-1.05 5.55-3.1 7.7M11 4.3C8.95 6.45 7.9 9 7.9 12s1.05 5.55 3.1 7.7"/>
    """, "Minecraft title action"),
    icon("title.quit", "Quit Game", "TITLE", """
      <path d="M10 4H6.3A2.3 2.3 0 0 0 4 6.3v11.4A2.3 2.3 0 0 0 6.3 20H10"/>
      <path d="M13 8.2 16.8 12 13 15.8M8.5 12h8"/>
      <path d="M20 4v16" opacity=".42"/>
    """, "Minecraft title action"),
    icon("title.language", "Language", "TITLE", """
      <path d="M12 3.5a8.5 8.5 0 1 1-5.1 15.3L3.5 20l1.2-3.4A8.5 8.5 0 0 1 12 3.5Z"/>
      <path d="M3.8 11.7h16.4M12 3.5c2.15 2.3 3.25 5.05 3.25 8.2S14.15 17.6 12 20.2M12 3.5c-2.15 2.3-3.25 5.05-3.25 8.2 0 2.15.52 4.12 1.55 5.9"/>
    """, "Minecraft title language action"),
    icon("title.accessibility", "Accessibility", "TITLE", """
      <circle cx="12" cy="4.8" r="2"/>
      <path d="M4 9.2c2.45 1.05 5.1 1.55 8 1.55s5.55-.5 8-1.55M12 10.75v3.1M7.2 20l4.8-6.15L16.8 20M8.5 10.35l-1.8 4.4M15.5 10.35l1.8 4.4"/>
    """, "Minecraft title accessibility action"),

    # Client chrome.
    icon("client.@backing", "Background", "CLIENT CHROME", """
      <rect x="6" y="3" width="15" height="14" rx="2.5" opacity=".5"/>
      <rect x="3" y="7" width="15" height="14" rx="2.5"/>
      <path d="M6.2 16.5 9.3 13l2.45 2.5 2-2 2 2.1"/>
    """, "Menu background settings"),
    icon("client.@general", "General", "CLIENT CHROME", """
      <path d="M4 6h3M11 6h9M4 12h9.5M17.5 12H20M4 18h1M9 18h11"/>
      <circle cx="9" cy="6" r="2"/>
      <circle cx="15.5" cy="12" r="2"/>
      <circle cx="7" cy="18" r="2"/>
    """, "Client general settings"),
    icon("client.@hudeditor", "HUD Editor", "CLIENT CHROME", """
      <rect x="3" y="3" width="18" height="18" rx="3"/>
      <path d="M9.5 3v18M11.5 12H21"/>
    """, "HUD layout editor"),
    icon("client.@performance", "Performance", "CLIENT CHROME", """
      <path d="M4.2 17.8a9 9 0 1 1 15.6 0"/>
      <path d="m12 14 4.4-5.1"/>
      <circle cx="12" cy="14" r="1.5" fill="currentColor" stroke="none"/>
      <path d="M7.1 17.8h9.8"/>
    """, "Client performance settings"),
    icon("client.@search", "Search", "CLIENT CHROME", """
      <circle cx="10" cy="10" r="6.5"/>
      <path d="m15 15 5.5 5.5"/>
    """, "Search fields"),

    # Client feature icons.
    icon("client.fps", "FPS", "HUD", """
      <rect x="3" y="4" width="18" height="15" rx="3"/>
      <path d="M6.5 14.5 10 11l2.6 2.2 4.9-5"/>
      <path d="M8.5 22h7M12 19v3"/>
    """, "Frames-per-second HUD"),
    icon("client.cps", "Clicks Per Second", "HUD", """
      <rect x="6.3" y="2.5" width="11.4" height="19" rx="5.7"/>
      <path d="M12 2.8v7.4M6.4 10.2h11.2"/>
    """, "Mouse-click rate HUD"),
    icon("client.keystrokes", "Keystrokes", "HUD", """
      <rect x="9.25" y="2.5" width="5.5" height="5.5" rx="1.4"/>
      <rect x="2" y="9.75" width="5.5" height="5.5" rx="1.4"/>
      <rect x="9.25" y="9.75" width="5.5" height="5.5" rx="1.4"/>
      <rect x="16.5" y="9.75" width="5.5" height="5.5" rx="1.4"/>
      <rect x="5" y="18" width="14" height="3.5" rx="1.4"/>
    """, "Keyboard input HUD"),
    icon("client.coords", "Coordinates", "HUD", """
      <path d="M5 19V5M5 19h14"/>
      <path d="m5 5-2.2 2.2M5 5l2.2 2.2M19 19l-2.2-2.2M19 19l-2.2 2.2"/>
      <circle cx="12.5" cy="11.5" r="2.2"/>
      <path d="M12.5 7.5v1.8M12.5 13.7v1.8M8.5 11.5h1.8M14.7 11.5h1.8"/>
    """, "World-coordinate HUD"),
    icon("client.armorhud", "Armor HUD", "HUD", """
      <path d="M12 2.8 19.5 6v5.8c0 4.3-2.5 7.3-7.5 9.4-5-2.1-7.5-5.1-7.5-9.4V6Z"/>
      <path d="M8 10h8M8 14h5"/>
    """, "Armor durability HUD"),
    icon("client.potionhud", "Potion HUD", "HUD", """
      <path d="M9 3h6M10 3v5l-5 8.5A2.8 2.8 0 0 0 7.4 21h9.2a2.8 2.8 0 0 0 2.4-4.5L14 8V3"/>
      <path d="M7.2 15h9.6"/>
      <circle cx="11" cy="18" r="1" fill="currentColor" stroke="none"/>
      <circle cx="14.7" cy="16.9" r=".7" fill="currentColor" stroke="none"/>
    """, "Potion-effect HUD"),
    icon("client.serveraddress", "Server Address", "HUD", """
      <rect x="3" y="4" width="18" height="16" rx="3"/>
      <path d="M3 8h18M7 11l3 3-3 3M13 17h4"/>
    """, "Current server HUD"),
    icon("client.scoreboard", "Scoreboard", "HUD", """
      <path d="M5 4h14v16H5z"/>
      <path d="M8 8h2M8 12h2M8 16h2M13 8h3M13 12h3M13 16h3"/>
    """, "Scoreboard HUD"),
    icon("client.tablist", "Tab List", "HUD", """
      <rect x="3" y="3" width="18" height="18" rx="3"/>
      <circle cx="8" cy="8" r="1.6"/>
      <circle cx="8" cy="16" r="1.6"/>
      <path d="M12 8h5M12 16h5"/>
    """, "Player roster"),
    icon("client.zoom", "Zoom", "VISUAL", """
      <circle cx="9.8" cy="9.8" r="6.3"/>
      <path d="M7 9.8h5.6M9.8 7v5.6M14.4 14.4l6.1 6.1"/>
    """, "Camera zoom"),
    icon("client.freelook", "Freelook", "VISUAL", """
      <path d="M2.7 12s3.4-6.1 9.3-6.1 9.3 6.1 9.3 6.1-3.4 6.1-9.3 6.1S2.7 12 2.7 12Z"/>
      <circle cx="12" cy="12" r="3.2"/>
      <circle cx="13.1" cy="10.9" r=".8" fill="currentColor" stroke="none"/>
    """, "Independent camera look"),
    icon("client.fullbright", "Fullbright", "VISUAL", """
      <circle cx="12" cy="12" r="4.2"/>
      <path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M18.7 5.3l-1.6 1.6M6.9 17.1l-1.6 1.6"/>
    """, "Lighting override"),
    icon("client.blockoverlay", "Block Overlay", "VISUAL", """
      <path d="m12 2.8 8.5 4.8v8.8L12 21.2l-8.5-4.8V7.6Z"/>
      <path d="m3.5 7.6 8.5 4.8 8.5-4.8M12 12.4v8.8"/>
      <path d="m7.2 5.5 8.6 4.8" opacity=".48"/>
    """, "Selected-block outline"),
    icon("client.chunkborders", "Chunk Borders", "VISUAL", """
      <rect x="3" y="3" width="18" height="18" rx="2.5"/>
      <path d="M9 3v18M15 3v18M3 9h18M3 15h18"/>
      <circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/>
    """, "Chunk boundary grid"),
    icon("client.hitboxes", "Hitboxes", "VISUAL", """
      <path d="M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5"/>
      <circle cx="12" cy="8.2" r="2.3"/>
      <path d="M8.5 17v-3.1c0-1.8 1.55-3.2 3.5-3.2s3.5 1.4 3.5 3.2V17"/>
    """, "Entity hitboxes"),
    icon("client.nametags", "Nametags", "VISUAL", """
      <rect x="3" y="2.5" width="18" height="6.5" rx="2"/>
      <path d="M7 5.75h10"/>
      <circle cx="12" cy="13.2" r="2.5"/>
      <path d="M7 21v-1.3c0-2.25 2.2-3.8 5-3.8s5 1.55 5 3.8V21"/>
    """, "Player nametag styling"),
    icon("client.itemsize", "Item Size", "VISUAL", """
      <rect x="8" y="8" width="8" height="8" rx="1.5"/>
      <path d="M16 8l4-4M16.5 4H20v3.5M8 16l-4 4M7.5 20H4v-3.5"/>
    """, "Dropped-item scale"),
    icon("client.weather", "Weather", "VISUAL", """
      <circle cx="5.7" cy="5.7" r="2"/>
      <path d="M5.7 2.7v-1M5.7 8.7v1M2.7 5.7h-1M8.7 5.7h1M3.58 3.58l-.71-.71M7.82 7.82l.71.71M7.82 3.58l.71-.71M3.58 7.82l-.71.71"/>
      <path d="M9 17h9a3 3 0 0 0 .2-6 4.8 4.8 0 0 0-9.1 1.4A2.4 2.4 0 0 0 9 17Z"/>
      <path d="m8.5 19-1 2M13 19l-1 2M17.5 19l-1 2"/>
    """, "Weather override"),
    icon("client.timechanger", "Time Changer", "VISUAL", """
      <circle cx="12" cy="12" r="8.5"/>
      <path d="M12 7v5l3.5 2"/>
      <path d="M12 3.5v1M20.5 12h-1M12 20.5v-1M3.5 12h1"/>
    """, "Client-side time"),
    icon("client.motionblur", "Motion Blur", "VISUAL", """
      <path d="M3 7h7M5 12h5M3 17h7"/>
      <circle cx="15.5" cy="12" r="5.5"/>
      <path d="M13.5 7a5.5 5.5 0 0 0 0 10" opacity=".45"/>
    """, "Motion blur effect"),
    icon("client.colorsaturation", "Color Saturation", "VISUAL", """
      <path d="M12 2.8S5.5 10 5.5 14.5a6.5 6.5 0 0 0 13 0C18.5 10 12 2.8 12 2.8Z"/>
      <path d="M7.2 16.3c2.7-1.4 6.9-1.4 9.6 0"/>
      <path d="M8.7 12.2h6.6" opacity=".5"/>
    """, "Color intensity"),
    icon("client.particles", "Particles", "VISUAL", """
      <path d="m9 3 .9 3.1L13 7l-3.1.9L9 11l-.9-3.1L5 7l3.1-.9ZM17.5 11l.7 2.3 2.3.7-2.3.7-.7 2.3-.7-2.3-2.3-.7 2.3-.7Z"/>
      <circle cx="6" cy="17.5" r="1.5"/>
      <circle cx="13" cy="20" r="1" fill="currentColor" stroke="none"/>
    """, "Particle controls"),
    icon("client.togglesprint", "Toggle Sprint", "GAMEPLAY", """
      <circle cx="14.8" cy="4.3" r="1.9" fill="currentColor" stroke="none"/>
      <path d="m12.6 8.1-2.7 5"/>
      <path d="m12.6 8.1 3.2 2.6 3.4-1.1"/>
      <path d="m12 9.2-2.8-1-2.1 2.1"/>
      <path d="m9.9 13.1 4.1 2 1.6 5"/>
      <path d="m9.9 13.1-3 3.6H3.2"/>
    """, "Sprint toggle"),
    icon("client.chat", "Chat", "GAMEPLAY", """
      <path d="M4.5 4h15A2.5 2.5 0 0 1 22 6.5v8a2.5 2.5 0 0 1-2.5 2.5H10l-5.5 4v-4A2.5 2.5 0 0 1 2 14.5v-8A2.5 2.5 0 0 1 4.5 4Z"/>
      <path d="M6.5 8.5h11M6.5 12.5H14"/>
    """, "Chat enhancements"),
    icon("client.waypoints", "Waypoints", "GAMEPLAY", """
      <path d="M12 21s6-5.5 6-11a6 6 0 1 0-12 0c0 5.5 6 11 6 11Z"/>
      <circle cx="12" cy="10" r="2.2"/>
      <path d="M4 21h16" opacity=".5"/>
    """, "World waypoints"),
    icon("client.jei", "Item Browser", "GAMEPLAY", """
      <rect x="3" y="3" width="18" height="18" rx="3"/>
      <path d="M3 7.5h18"/>
      <circle cx="6" cy="5.3" r=".65" fill="currentColor" stroke="none"/>
      <rect x="6" y="10" width="4" height="3.4" rx=".8"/>
      <rect x="14" y="10" width="4" height="3.4" rx=".8"/>
      <rect x="6" y="15.7" width="4" height="2.3" rx=".7"/>
      <rect x="14" y="15.7" width="4" height="2.3" rx=".7"/>
    """, "JEI item/recipe browser"),

    # Lunar-parity batch 1 (2026-10-11) — same grid, stroke and round caps.
    icon("client.clock", "Clock", "HUD", """
      <rect x="6" y="6" width="12" height="12" rx="3.5"/>
      <path d="M9 6l.8-3.5h4.4L15 6M9 18l.8 3.5h4.4L15 18"/>
      <path d="M12 9.2V12l1.9 1.3"/>
    """, "Real-time clock HUD"),
    icon("client.stopwatch", "Stopwatch", "HUD", """
      <circle cx="12" cy="13.5" r="7.5"/>
      <path d="M10 2.5h4M12 2.5V6M18.2 7.3l1.4-1.4"/>
      <path d="M12 13.5l3-3"/>
    """, "Stopwatch HUD"),
    icon("client.daycounter", "Day Counter", "HUD", """
      <rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/>
      <path d="M3.5 9.5h17M8 3v4M16 3v4"/>
      <circle cx="12" cy="15" r="2.3"/>
    """, "In-game day HUD"),
    icon("client.ping", "Ping", "HUD", """
      <path d="M5 19.5v-3M9.5 19.5v-6.5M14 19.5v-10M18.5 19.5v-14"/>
    """, "Connection latency HUD"),
    icon("client.memory", "Memory Usage", "HUD", """
      <rect x="3" y="6.5" width="18" height="10" rx="2"/>
      <path d="M7.5 10v3M12 10v3M16.5 10v3M6 16.5V20M10 16.5V20M14 16.5V20M18 16.5V20"/>
    """, "Memory usage HUD"),
    icon("client.saturation", "Saturation", "HUD", """
      <ellipse cx="14.3" cy="9.7" rx="6.4" ry="5" transform="rotate(-45 14.3 9.7)"/>
      <path d="M9.8 14.2 7 17"/>
      <circle cx="5.6" cy="16.9" r="1.5"/>
      <circle cx="7.1" cy="18.4" r="1.5"/>
      <path d="M12.2 7.4a3.2 3.2 0 0 1 2.9-1.1" opacity=".5"/>
    """, "Hunger saturation"),
    icon("client.packdisplay", "Pack Display", "HUD", """
      <path d="m12 3 9 4.5-9 4.5-9-4.5Z"/>
      <path d="m3 12 9 4.5 9-4.5M3 16.5 12 21l9-4.5"/>
    """, "Active resource pack HUD"),
    icon("client.itemcounter", "Item Counter", "HUD", """
      <rect x="2.5" y="3.5" width="13" height="13" rx="2.5"/>
      <path d="M6.2 9.2 9 6.4l2.8 2.8L9 13Z"/>
      <path d="M18.2 13.5v7M21.2 13.5v7"/>
    """, "Inventory item counts"),
    icon("client.directionhud", "Direction HUD", "HUD", """
      <circle cx="12" cy="12" r="9"/>
      <path d="m15.6 8.4-2.3 4.9-4.9 2.3 2.3-4.9Z"/>
    """, "Compass heading strip"),
    icon("client.combo", "Combo Counter", "HUD", """
      <path d="M4 4l9.5 9.5M20 4l-9.5 9.5"/>
      <path d="M6.6 14.6l2.8 2.8M17.4 14.6l-2.8 2.8M4.5 19.5l3-3M19.5 19.5l-3-3"/>
    """, "Hit combo HUD"),
    icon("client.reach", "Reach Display", "HUD", """
      <path d="M3 12h18M6 9l-3 3 3 3M18 9l3 3-3 3"/>
      <path d="M9 9.5v5M12 8.5v7M15 9.5v5" opacity=".55"/>
    """, "Hit distance HUD"),
    icon("client.crosshair", "Crosshair", "VISUAL", """
      <circle cx="12" cy="12" r="6.5"/>
      <path d="M12 2.5v5M12 16.5v5M2.5 12h5M16.5 12h5"/>
    """, "Custom crosshair"),
]

# Lunar parity, batch 2 (2026-10-11): visuals / feel mods. Same 24-grid, 1.55
# stroke, one concept per glyph. Kept as its own list so it merges cleanly.
LUNAR_BATCH2_ICONS = [
    icon("client.hurtcam", "Hurt Cam", "VISUAL", """
      <rect x="5" y="6.5" width="14" height="11" rx="2.5" transform="rotate(-12 12 12)"/>
      <path d="M2.4 9.6v4.8M21.6 9.6v4.8"/>
      <path d="M10 11.2l2 1.6 2-1.6"/>
    """, "Damage camera shake"),
    icon("client.hitcolor", "Hit Color", "VISUAL", """
      <path d="M12 20.2s-7.6-4.5-7.6-10A4.2 4.2 0 0 1 12 7.6a4.2 4.2 0 0 1 7.6 2.6c0 5.5-7.6 10-7.6 10Z"/>
      <path d="M18.6 2.8l-.9 2.2M21.6 5.6l-2.2.9"/>
    """, "Damage flash colour"),
    icon("client.glintcolorizer", "Glint Colorizer", "VISUAL", """
      <path d="M20 4v3.4l-8.8 8.8-3.4-3.4L16.6 4Z"/>
      <path d="M6.6 11.4l6 6M9.4 14.6l-4.9 4.9"/>
      <path d="M6 2.8v3.4M4.3 4.5h3.4"/>
    """, "Enchantment glint colour"),
    icon("client.bossbar", "Boss Bar", "HUD", """
      <rect x="2.8" y="12" width="18.4" height="5.2" rx="1.4"/>
      <path d="M5.4 14.6h7.6M8 7.5h8"/>
    """, "Boss health bar"),
    icon("client.titles", "Titles", "HUD", """
      <path d="M6 5h12M12 5v9.5M8.5 19h7"/>
    """, "On-screen titles"),
    icon("client.menublur", "Menu Blur", "VISUAL", """
      <rect x="3" y="4" width="18" height="16" rx="3"/>
      <path d="M7 10c1.5-1.2 3-1.2 4.5 0s3 1.2 4.5 0M7 14.5c1.5-1.2 3-1.2 4.5 0s3 1.2 4.5 0"/>
    """, "Blurred menu backdrop"),
    icon("client.shinypots", "Shinypots", "VISUAL", """
      <path d="M8.8 3h5M9.6 3v4.6a6.2 6.2 0 1 0 3.6 0V3"/>
      <path d="M8.2 15.2h6.4"/>
      <path d="M19.2 3.2v3.4M17.5 4.9h3.4"/>
    """, "Shining potions"),
    icon("client.tntcountdown", "TNT Countdown", "VISUAL", """
      <rect x="3.5" y="9" width="12" height="12" rx="2"/>
      <path d="M3.5 15h12M9.5 9c0-2.6 1.4-4.4 4.2-4.9"/>
      <path d="M17.3 2.6l.8 1.6M20.6 5.2l-1.7.6M20.2 1.9l-1.2 1.4"/>
    """, "TNT fuse timer"),
    icon("client.snaplook", "Snaplook", "GAMEPLAY", """
      <path d="M19 9H8.5a4.5 4.5 0 0 0 0 9H15"/>
      <path d="m15.5 5.5 3.5 3.5-3.5 3.5"/>
    """, "Look behind"),
    icon("client.cooldowns", "Cooldowns", "HUD", """
      <circle cx="12" cy="12" r="8.6"/>
      <path d="M12 3.4V12l6.1 6.1"/>
    """, "Item cooldown dials"),
    icon("client.soundchanger", "Sound Changer", "GAMEPLAY", """
      <path d="M3.5 9.3h3.6l4.6-4v13.4l-4.6-4H3.5Z"/>
      <path d="M15.4 9.2a4 4 0 0 1 0 5.6M18.2 6.6a7.6 7.6 0 0 1 0 10.8"/>
    """, "Per-sound volume"),
    icon("client.itemphysics", "Item Physics", "VISUAL", """
      <rect x="9" y="3.2" width="7" height="7" rx="1.4" transform="rotate(28 12.5 6.7)"/>
      <path d="M3 20.5h18M7.5 17.5h9"/>
      <path d="M5 8.5c-.9 1.6-.9 3.4 0 5"/>
    """, "Physics for dropped items"),
]
ICONS += LUNAR_BATCH2_ICONS

# Lunar parity, batch 4b (2026-10-11): WorldEdit CUI. Own list so it merges cleanly.
LUNAR_BATCH4B_ICONS = [
    icon("client.worldeditcui", "WorldEdit CUI", "VISUAL", """
      <path d="M12 3.2l7.6 4.4v8.8L12 20.8l-7.6-4.4V7.6Z"/>
      <path d="M4.4 7.6 12 12l7.6-4.4M12 12v8.8" opacity=".5"/>
      <circle cx="4.4" cy="16.4" r="1.9" fill="currentColor" stroke="none"/>
      <circle cx="19.6" cy="7.6" r="1.9" fill="currentColor" stroke="none"/>
    """, "WorldEdit selection box"),
]
ICONS += LUNAR_BATCH4B_ICONS

# Lunar parity, batch 3b (2026-10-11): server integrations. Same 24-grid, 1.55
# stroke, one concept per glyph; no third-party brand marks (Discord / Hypixel are
# drawn as a status bubble and a Bed Wars bed). Kept as its own list so it merges cleanly.
LUNAR_BATCH3B_ICONS = [
    icon("client.teamview", "Team View", "VISUAL", """
      <path d="M9.6 2.6h4.8L12 5.6Z"/>
      <circle cx="8.3" cy="11" r="2.5"/>
      <circle cx="15.7" cy="11" r="2.5"/>
      <path d="M3.4 20.4c.5-2.9 2.3-4.5 4.9-4.5s4.4 1.6 4.9 4.5"/>
      <path d="M13.9 16.3c.5-.3 1.1-.4 1.8-.4 2.6 0 4.4 1.6 4.9 4.5"/>
    """, "Teammate markers"),
    icon("client.discord", "Discord Status", "GAMEPLAY", """
      <path d="M5 6h8a2.5 2.5 0 0 1 2.5 2.5v5.7A2.5 2.5 0 0 1 13 16.7H8.8l-3.6 3v-3H5a2.5 2.5 0 0 1-2.5-2.5V8.5A2.5 2.5 0 0 1 5 6Z"/>
      <circle cx="19.2" cy="6.2" r="2.3"/>
      <path d="M6.5 11.4h5"/>
    """, "Rich presence status"),
    icon("client.hypixel", "Hypixel", "GAMEPLAY", """
      <path d="M3 18.5V7M3 14.5h18v4M21 14.5v-2a3 3 0 0 0-3-3h-7.5v5"/>
      <circle cx="6.8" cy="11.3" r="1.8"/>
    """, "Hypixel levels and Bed Wars stats"),
]
ICONS += LUNAR_BATCH3B_ICONS

# Lunar parity, batch 4a (2026-10-11): Minimap. A map frame with the player's
# arrowhead and a terrain contour. Its own list so it merges cleanly.
LUNAR_BATCH4A_ICONS = [
    icon("client.minimap", "Minimap", "HUD", """
      <rect x="3" y="3" width="18" height="18" rx="4"/>
      <path d="m12 6.2 3 7-3-1.6-3 1.6Z"/>
      <path d="M3.5 17c3-1.6 5.5-1.6 8.5 0s5.5 1.6 8.5 0" opacity=".55"/>
    """, "Live area map"),
]
ICONS += LUNAR_BATCH4A_ICONS

# Lunar parity, batch 3a (2026-10-11): chat / server mods. Same 24-grid, 1.55
# stroke, one concept per glyph. Its own list so it merges cleanly.
LUNAR_BATCH3A_ICONS = [
    icon("client.autotext", "Auto Text Hotkey", "GAMEPLAY", """
      <rect x="2.8" y="10.5" width="9" height="9" rx="2"/>
      <path d="M5.6 13.4h3.4M7.3 13.4v3.4"/>
      <path d="M14.2 3.8h5.9a1.9 1.9 0 0 1 1.9 1.9v3.8a1.9 1.9 0 0 1-1.9 1.9h-2.4l-2.7 2.3v-2.3h-.8a1.9 1.9 0 0 1-1.9-1.9V5.7a1.9 1.9 0 0 1 1.9-1.9Z"/>
    """, "Hotkey that sends chat"),
    icon("client.autogg", "Auto GG", "GAMEPLAY", """
      <path d="M8 3.8h8v5.4a4 4 0 0 1-8 0Z"/>
      <path d="M8 5.8H5.4a2.7 2.7 0 0 0 2.8 3.5M16 5.8h2.6a2.7 2.7 0 0 1-2.8 3.5"/>
      <path d="M12 13.2v3.8M8.5 20.2h7M10 17h4"/>
    """, "Automatic good game"),
    icon("client.nickhider", "Nick Hider", "VISUAL", """
      <circle cx="12" cy="8.4" r="3.6"/>
      <path d="M5 20c.8-3.6 3.5-5.6 7-5.6s6.2 2 7 5.6"/>
      <path d="M3.6 3.6l16.8 16.8"/>
    """, "Hidden names and skins"),
    icon("client.quickplay", "Quickplay", "GAMEPLAY", """
      <circle cx="14" cy="12" r="7.6"/>
      <path d="M12 8.8v6.4l5-3.2Z"/>
      <path d="M2.4 9h3M2 12h4M2.4 15h3"/>
    """, "Join a game fast"),
    icon("client.screenshot", "Screenshot", "GAMEPLAY", """
      <path d="M4.6 7.5h2.9l1.6-2.5h5.8l1.6 2.5h2.9a1.8 1.8 0 0 1 1.8 1.8v8.4a1.8 1.8 0 0 1-1.8 1.8H4.6a1.8 1.8 0 0 1-1.8-1.8V9.3a1.8 1.8 0 0 1 1.8-1.8Z"/>
      <circle cx="12" cy="13.2" r="3.5"/>
    """, "Screenshot pop-up"),
]
ICONS += LUNAR_BATCH3A_ICONS


def svg_document(entry: dict) -> str:
    title = html.escape(entry["label"])
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" color="#EEF0F2">
  <title>{title}</title>
  <g {STROKE}>
{entry["body"]}
  </g>
</svg>
'''


def write_contact_sheet() -> tuple[int, int]:
    cols = 6
    card_w, card_h = 276, 170
    gap_x, gap_y = 10, 10
    margin_x, header_h, bottom = 46, 174, 46
    rows = (len(ICONS) + cols - 1) // cols
    width = margin_x * 2 + cols * card_w + (cols - 1) * gap_x
    height = header_h + rows * card_h + (rows - 1) * gap_y + bottom

    out = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <rect width="100%" height="100%" fill="#090D0C"/>
  <path d="M0 0H{width}V5H0Z" fill="#4BC8AE"/>
  <text x="46" y="68" fill="#EFF7F4" font-family="Segoe UI, Inter, sans-serif" font-size="34" font-weight="700" letter-spacing="1">ORIGIN ICON SYSTEM</text>
  <text x="46" y="105" fill="#A7BBB5" font-family="Segoe UI, Inter, sans-serif" font-size="17">Proposal 05 · 24 px master grid · 1.55 px optical stroke · Coolicons-referenced discipline</text>
  <text x="46" y="137" fill="#4BC8AE" font-family="Segoe UI, Inter, sans-serif" font-size="14" font-weight="600" letter-spacing="2">{len(ICONS)} ORIGINAL MASTER SYMBOLS · EXTERNAL REVIEW ONLY</text>
''']

    for i, entry in enumerate(ICONS):
        col, row = i % cols, i // cols
        x = margin_x + col * (card_w + gap_x)
        y = header_h + row * (card_h + gap_y)
        category = html.escape(entry["category"])
        label = html.escape(entry["label"])
        icon_id = html.escape(entry["id"])
        out.append(f'''
  <g>
    <rect x="{x}" y="{y}" width="{card_w}" height="{card_h}" rx="13" fill="#121918" stroke="#263732"/>
    <rect x="{x+16}" y="{y+18}" width="78" height="78" rx="12" fill="#192320" stroke="#3B524C"/>
    <g transform="translate({x+25} {y+27}) scale(2.5)" color="#EFF7F4" {STROKE}>
{entry["body"]}
    </g>
    <text x="{x+110}" y="{y+48}" fill="#EFF7F4" font-family="Segoe UI, Inter, sans-serif" font-size="18" font-weight="600">{label}</text>
    <text x="{x+110}" y="{y+75}" fill="#6D817B" font-family="Consolas, monospace" font-size="12">{icon_id}</text>
    <rect x="{x+16}" y="{y+117}" width="{min(236, 24 + len(category)*7.2):.1f}" height="28" rx="7" fill="#13231F" stroke="#28584E"/>
    <text x="{x+29}" y="{y+136}" fill="#79DEC9" font-family="Segoe UI, Inter, sans-serif" font-size="11" font-weight="700" letter-spacing="1.1">{category}</text>
  </g>''')

    out.append("\n</svg>\n")
    (ROOT / "contact-sheet.svg").write_text("".join(out), encoding="utf-8")
    return width, height


def write_revision_sheet() -> tuple[int, int]:
    revised_ids = [
        "title.singleplayer", "title.language", "title.accessibility",
        "client.serveraddress", "launcher.loading", "client.weather",
    ]
    entries = [next(entry for entry in ICONS if entry["id"] == icon_id) for icon_id in revised_ids]
    cols = 4
    card_w, card_h = 290, 190
    gap = 12
    margin_x, header_h, bottom = 42, 150, 42
    rows = (len(entries) + cols - 1) // cols
    width = margin_x * 2 + cols * card_w + (cols - 1) * gap
    height = header_h + rows * card_h + (rows - 1) * gap + bottom
    out = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <rect width="100%" height="100%" fill="#090D0C"/>
  <path d="M0 0H{width}V5H0Z" fill="#4BC8AE"/>
  <text x="42" y="60" fill="#EFF7F4" font-family="Segoe UI, Inter, sans-serif" font-size="32" font-weight="700" letter-spacing="1">ICON REVISION 05</text>
  <text x="42" y="98" fill="#A7BBB5" font-family="Segoe UI, Inter, sans-serif" font-size="17">{len(entries)} refined master symbols · Ion Jade</text>
  <text x="42" y="126" fill="#4BC8AE" font-family="Segoe UI, Inter, sans-serif" font-size="12" font-weight="700" letter-spacing="1.7">INTEGRATED SYSTEM</text>
''']
    for i, entry in enumerate(entries):
        col, row = i % cols, i // cols
        x = margin_x + col * (card_w + gap)
        y = header_h + row * (card_h + gap)
        label = html.escape(entry["label"])
        icon_id = html.escape(entry["id"])
        category = html.escape(entry["category"])
        out.append(f'''
  <g>
    <rect x="{x}" y="{y}" width="{card_w}" height="{card_h}" rx="14" fill="#121918" stroke="#263732"/>
    <rect x="{x+18}" y="{y+18}" width="94" height="94" rx="14" fill="#192320" stroke="#3B524C"/>
    <g transform="translate({x+29} {y+29}) scale(3)" color="#EFF7F4" {STROKE}>
{entry["body"]}
    </g>
    <text x="{x+130}" y="{y+53}" fill="#EFF7F4" font-family="Segoe UI, Inter, sans-serif" font-size="18" font-weight="600">{label}</text>
    <text x="{x+130}" y="{y+82}" fill="#6D817B" font-family="Consolas, monospace" font-size="12">{icon_id}</text>
    <rect x="{x+18}" y="{y+137}" width="{min(248, 24 + len(category)*7.2):.1f}" height="29" rx="7" fill="#13231F" stroke="#28584E"/>
    <text x="{x+31}" y="{y+157}" fill="#79DEC9" font-family="Segoe UI, Inter, sans-serif" font-size="11" font-weight="700" letter-spacing="1.1">{category}</text>
  </g>''')
    out.append("\n</svg>\n")
    (ROOT / "revision-sheet.svg").write_text("".join(out), encoding="utf-8")
    return width, height


def main() -> None:
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    for entry in ICONS:
        slug = entry["id"].replace("@", "").replace(".", "-")
        (SVG_DIR / f"{slug}.svg").write_text(svg_document(entry), encoding="utf-8")

    manifest = {
        "name": "Origin Icon System — Proposal 05",
        "status": "approved-integrated",
        "reference": "Coolicons v4.1 — proportion and stroke discipline only; all artwork is original",
        "grid": 24,
        "stroke": 1.55,
        "style": "precision rounded outline",
        "reusedTitleSymbols": {
            "title.mods": "launcher.mods",
            "title.options": "launcher.settings",
        },
        "icons": [{k: v for k, v in entry.items() if k != "body"} for entry in ICONS],
    }
    (ROOT / "icon-index.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    width, height = write_contact_sheet()
    revision_width, revision_height = write_revision_sheet()
    print(f"Generated {len(ICONS)} SVG icons, contact sheet {width}x{height}, "
          f"and revision sheet {revision_width}x{revision_height}")


if __name__ == "__main__":
    main()
