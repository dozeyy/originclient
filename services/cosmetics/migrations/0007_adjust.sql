-- 2026-10-18 (Will): players move their cosmetics up or down to suit their skin.
-- JSON {"cape": 1.5, "head": -0.5, ...}: model px per slot (FORMAT.md "Height
-- tweaks"); NULL = every slot at its default spot.
ALTER TABLE equipped ADD COLUMN adjust TEXT;
