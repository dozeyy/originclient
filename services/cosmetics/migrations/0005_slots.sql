-- Cosmetics v2 (2026-10-17): three more slots. back = wings or a backpack,
-- face = a bandana or mask, theme = a menu colour theme (cosmetics/FORMAT.md).
ALTER TABLE equipped ADD COLUMN face TEXT;
ALTER TABLE equipped ADD COLUMN back TEXT;
ALTER TABLE equipped ADD COLUMN theme TEXT;
