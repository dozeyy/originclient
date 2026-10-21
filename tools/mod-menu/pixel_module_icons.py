"""Colour pixel art for the mod menu's module + tab icons (12 x 12, hard pixels).

Data only: {id: (rows, palette)}. 'k' = the shared dark outline, '.' = transparent, every other
letter is looked up in that icon's palette. pixel_title_icons.py turns these into the atlas.
Light from the top-left: the lightest tone sits top-left, the darkest bottom-right.
"""

ART: dict[str, tuple[list[str], dict[str, str]]] = {}

ART["fps"] = ([
    "kkkkkkkkkkkk",
    "kwwwwwwwwwwk",
    "kwnnnnnnnndk",
    "kwnnnnnnGndk",
    "kwnnnGnngndk",
    "kwnGngngnndk",
    "kwngggnggndk",
    "kddddddddddk",
    "kkkkkddkkkkk",
    "....kddk....",
    "...kkkkkk...",
], {"w": "#d8d8e0", "d": "#7c7c8a", "n": "#101c2c", "g": "#4cc84c", "G": "#b4ffb0"})

ART["cps"] = ([
    "....kkkk....",
    "...kRrkwwbk.",
    "..kRrrkwwwbk",
    "..kRrrkwwwbk",
    "..kkkkkkkkkk",
    "..kwwwwwwwbk",
    "..kwwwwwwwbk",
    "..kwwwwwwbdk",
    "..kwwwwwbbdk",
    "...kwwwbbdk.",
    "....kkkkkk..",
], {"w": "#f2f2f6", "b": "#b4b4c2", "d": "#80808e", "r": "#e04040", "R": "#ff8888"})

ART["keystrokes"] = ([
    "kkkkkkkkkkkk",
    "kwwwwwwwwwbk",
    "kwbbbbbbbbdk",
    "kwbtbbbbtbdk",
    "kwbtbbbbtbdk",
    "kwbtbttbtbdk",
    "kwbbtbbtbbdk",
    "kwbbbbbbbbdk",
    "kdddddddddbk",
    "kkkkkkkkkkkk",
], {"w": "#f6f6fa", "b": "#c4c4d0", "d": "#8a8a98", "t": "#3a3a48"})

ART["coords"] = ([
    "....kkkk....",
    "...kRRrrk...",
    "..kRrrrrrk..",
    "..kRrwwrrk..",
    "..kRrwwrdk..",
    "..kRrrrrdk..",
    "...kRrrdk...",
    "...kRrrdk...",
    "....krdk....",
    ".....kk.....",
], {"r": "#e84848", "R": "#ff9a9a", "d": "#a02020", "w": "#ffffff"})

ART["armorhud"] = ([
    ".kkkk..kkkk.",
    "kwcck..kbbdk",
    "kwccckkcbbdk",
    ".kwccccbbdk.",
    ".kwccccbbdk.",
    ".kwccccbbdk.",
    ".kwccccbbdk.",
    ".kwccccbbdk.",
    ".kcccbbbbdk.",
    ".kkkkkkkkkk.",
], {"w": "#f4f4f8", "c": "#c8c8d2", "b": "#9c9ca8", "d": "#686874"})

ART["potionhud"] = ([
    "....kkkk....",
    "....kcck....",
    "....kggk....",
    "....kggk....",
    "...kgllgk...",
    "..kgllppgk..",
    ".kgllpppdgk.",
    ".kllpppppdk.",
    ".kpppppppdk.",
    "..kppppddk..",
    "...kkkkkk...",
], {"c": "#9c6a36", "g": "#d4eefa", "l": "#ff7aa8", "p": "#e0306a", "d": "#9a1c48"})

ART["zoom"] = ([
    "..kkkkkk....",
    ".kwwlllbbk..",
    "kwwlllllbbk.",
    "kwllllllbbk.",
    "kllllllbbdk.",
    "kllllbbbbdk.",
    "kbbbbbbbddk.",
    ".kbbbbdddkk.",
    "..kkkdddkHk.",
    ".......kHHHk",
    "........kHHk",
    ".........kk.",
], {"w": "#ffffff", "l": "#c4e8ff", "b": "#6cb4f0", "d": "#3a7cc0", "H": "#a8703c"})

ART["chat"] = ([
    "kkkkkkkkkkkk",
    "kwwwwwwwwwwk",
    "kwtttwtttwdk",
    "kwwwwwwwwwdk",
    "kwtttttwwwdk",
    "kwwwwwwwwwdk",
    "kbbbbbbbbbdk",
    "kkkkkkwbkkkk",
    ".....kbk....",
    "......k.....",
], {"w": "#f8f8fc", "b": "#c4c4d0", "d": "#9090a0", "t": "#505060"})

ART["minimap"] = ([
    "kkkkkkkkkkkk",
    "kppggppwppbk",
    "kpgggppwpgbk",
    "kpgggwwwpgbk",
    "kppgppwppgbk",
    "kwwwwwwwwwwk",
    "kppppwpgggbk",
    "kpgpwwpgggbk",
    "kppppwppggbk",
    "kkkkkkkkkkkk",
], {"p": "#e8d8a0", "b": "#bca868", "g": "#5cb848", "w": "#4a90d8"})

ART["crosshair"] = ([
    "....kkkk....",
    "....kwwk....",
    "....kwwk....",
    "kkkkkwwkkkkk",
    "kwwwwwwwwwwk",
    "kwwwwrrwwwwk",
    "kkkkkwwkkkkk",
    "....kwwk....",
    "....kwwk....",
    "....kkkk....",
], {"w": "#f4f4f8", "r": "#e84848"})

ART["particles"] = ([
    "....kk......",
    "...kYYk.....",
    "...kYYk..kk.",
    "kkkkYWYkkkYk",
    "kYYYYWYYYYk.",
    "kkkkYWYkkkYk",
    "...kYYk..kk.",
    "...kYYk.....",
    "....kk......",
], {"Y": "#ffd848", "W": "#fffbd0"})

ART["weather"] = ([
    "....kkkk....",
    "..kkwwwwkk..",
    ".kwwwwwwwwk.",
    "kwwwwwwwwwdk",
    "kwwwwwwwwddk",
    "kdddddddddkk",
    ".kkkkkkkkk..",
    "..b..b..b...",
    ".b..b..b....",
    "..b..b..b...",
], {"w": "#f2f2f8", "d": "#a8a8b8", "b": "#4a90f0"})

ART["ping"] = ([
    "............",
    "........kkk.",
    "........kGk.",
    ".....kkkkGk.",
    ".....kGGkGk.",
    "..kkkkGGkGk.",
    "..kGGkGGkGk.",
    "kkkGGkGGkGk.",
    "kGkGGkGGkGk.",
    "kkkkkkkkkkk.",
], {"G": "#58d058"})

ART["clock"] = ([
    "....kkkk....",
    "..kkwwwwkk..",
    ".kwwwwdwwwk.",
    "kwwwwwdwwwwk",
    "kwwwwwdwwwwk",
    "kwwwwwddddwk",
    "kwwwwwwwwwbk",
    ".kwwwwwwwbk.",
    "..kkbbbbkk..",
    "....kkkk....",
], {"w": "#f6e8a0", "b": "#c8a030", "d": "#4a3a10"})

ART["screenshot"] = ([
    "...kkkkk....",
    "kkkkwwwkkkkk",
    "kwwwwwwwwwwk",
    "kwwkkkkkwwdk",
    "kwkLLLLLkwdk",
    "kwkLlllLkwdk",
    "kwkLLLLLkwdk",
    "kwwkkkkkwwdk",
    "kddddddddddk",
    "kkkkkkkkkkkk",
], {"w": "#dcdce4", "d": "#8a8a98", "L": "#2a62b8", "l": "#9ad0ff"})

# Mod-menu tabs / chrome
ART["@general_old"] = ([
    ".....kk.....",
    "..kk.kwk.kk.",
    ".kwwkkwkkwwk",
    "..kwwwwwwdk.",
    ".kkwwkkkwdkk",
    "kwwwknnkwddk",
    ".kkwwkkkwdkk",
    "..kwwwwwddk.",
    ".kwwkkdkkddk",
    "..kk.kdk.kk.",
    ".....kk.....",
], {"w": "#d8d8e2", "d": "#74748a", "n": "#2a2a36"})

ART["@performance"] = ([
    ".......kkk..",
    "......kYYk..",
    ".....kYYk...",
    "....kYYk....",
    "...kYYYkkk..",
    "..kYYYYYYk..",
    "..kkkkYYk...",
    ".....kYk....",
    "....kYk.....",
    "....kk......",
], {"Y": "#ffd648"})

ART["@hudeditor"] = ([
    "kkkkkkkkkkkk",
    "kbbbbbbbbbbk",
    "kkkkkkkkkkkk",
    "kwwwkwwwwwwk",
    "kwwwkwwwwwwk",
    "kwwwkkkkkkkk",
    "kwwwkwwwkwwk",
    "kwwwkwwwkwwk",
    "kdddkdddkddk",
    "kkkkkkkkkkkk",
], {"w": "#e8e8f0", "d": "#9090a4", "b": "#5a8cf0"})

ART["@search"] = ART["zoom"]
