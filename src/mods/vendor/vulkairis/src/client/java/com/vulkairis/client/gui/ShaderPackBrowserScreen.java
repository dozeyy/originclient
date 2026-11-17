package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.ShaderPackService;
import com.vulkairis.translation.TranslationWorker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shader pack browser (GUI plan Phase 5): live folder cards, search, rescan, selection and
 * testing. Cards come only from the current scan result — never a built-in list. Selection
 * is browser selection (inspect/test), not pack activation, which stays with Iris.
 */
public class ShaderPackBrowserScreen extends Screen {

    private final Screen parent;
    private final ShaderPacksCategoryModel model;
    private EditBox search;
    private PackList list;
    private Button testButton;
    private TranslationWorker.Task<Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport>> pendingFull;
    private String notice = "";

    public ShaderPackBrowserScreen(Screen parent, ShaderPacksCategoryModel model) {
        super(Component.literal("Shader Packs"));
        this.parent = parent;
        this.model = model;
    }

    @Override
    protected void init() {
        clearWidgets();
        search = new EditBox(font, 12, 30, width - 24, 20, Component.literal("Search packs"));
        search.setHint(Component.literal("Search packs..."));
        search.setResponder(query -> refreshCards());
        addRenderableWidget(search);

        list = new PackList(minecraft, width - 24, height - 130, 56, 52);
        refreshCards();
        addRenderableWidget(list);

        int buttonY = height - 58;
        addRenderableWidget(Button.builder(Component.literal("Rescan"), button -> onRescan())
                .bounds(12, buttonY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open Folder"), button -> onOpenFolder())
                .bounds(106, buttonY, 100, 20).build());
        testButton = Button.builder(Component.literal("Test Selected"), button -> onTest())
                .bounds(210, buttonY, 110, 20).build();
        addRenderableWidget(testButton);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> model.cancelTest())
                .bounds(324, buttonY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width - 112, buttonY, 100, 20).build());

        model.configuredDirectory().ifPresentOrElse(
                directory -> model.rescanAsync(directory),
                () -> notice = "Iris is not installed; no folder to scan.");
    }

    private void onRescan() {
        Optional<Path> directory = model.configuredDirectory();
        if (directory.isEmpty()) {
            notice = "Iris is not installed; no folder to scan.";
            return;
        }
        notice = "Scanning…";
        model.rescanAsync(directory.get());
    }

    private void onOpenFolder() {
        Optional<Path> directory = model.configuredDirectory();
        if (directory.isPresent()) {
            try {
                net.minecraft.Util.getPlatform().openPath(directory.get());
                return;
            } catch (Throwable t) {
                notice = "Could not open the folder: " + t.getMessage();
                return;
            }
        }
        notice = "Iris is not installed; no folder to open.";
    }

    private void onTest() {
        Optional<Path> directory = model.configuredDirectory();
        if (directory.isEmpty()) {
            notice = "Iris is not installed; no folder to test from.";
            return;
        }
        if (model.selectedPack().isEmpty()) {
            notice = "Select a pack card first.";
            return;
        }
        notice = "Testing…";
        pendingFull = model.testFullAsync(directory.get());
    }

    @Override
    public void tick() {
        super.tick();
        if (model.collectScan()) {
            notice = "";
            refreshCards();
        }
        if (pendingFull != null && pendingFull.future().isDone()) {
            boolean installed = model.collectFullTest(pendingFull);
            pendingFull = null;
            notice = installed ? "" : "Test result was stale or cancelled; rescan and retry.";
            refreshCards();
        }
        if (testButton != null) {
            testButton.active = model.selectedPack().isPresent() && pendingFull == null
                    && !model.scanPending() && !model.isTestRunning();
        }
    }

    private void refreshCards() {
        if (list == null) {
            return;
        }
        String query =
                search != null ? search.getValue().toLowerCase(Locale.ROOT).trim() : "";
        list.setCards(model, query);
        if (testButton == null) {
            return;
        }
        testButton.active = model.selectedPack().isPresent() && pendingFull == null
                && !model.scanPending() && !model.isTestRunning();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, "Shader Packs — live folder scan", 12, 12, 0xFFFFFFFF);
        Optional<Path> directory = model.configuredDirectory();
        graphics.drawString(font,
                directory.map(Path::toString).orElse("no folder"), 12, 22, 0xFF808080);
        if (model.scanPending() || pendingFull != null || model.isTestRunning()) {
            String progress = "Testing…";
            if (model.progressTotal() > 0) {
                progress = "Testing " + model.progressDone() + "/" + model.progressTotal() + "…";
            }
            graphics.drawString(font, model.scanPending() ? "Scanning…" : progress,
                    width - 140, 12, 0xFFFF55);
        }
        var scan = model.lastScan();
        if (scan.isPresent() && !scan.get().warnings().isEmpty()) {
            String warning = scan.get().warnings().get(0).fileName() + ": "
                    + scan.get().warnings().get(0).reason();
            graphics.drawString(font, font.plainSubstrByWidth("⚠ " + warning + " (+"
                    + (scan.get().warnings().size() - 1) + " more)", width - 24), 12,
                    height - 72, 0xFFFF55);
        }
        if (!notice.isEmpty()) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(notice, width - 40),
                    width / 2, height - 72, 0xFFFF55);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Cards plus warning entries, filtered by the search query. */
    static class PackList extends ObjectSelectionList<PackList.PackEntry> {
        PackList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void setCards(ShaderPacksCategoryModel model, String query) {
            clearEntries();
            var scan = model.lastScan();
            if (scan.isEmpty()) {
                addEntry(new PackEntry("Waiting for scan…", "", 0xFF808080, false, null));
                return;
            }
            var packs = new ArrayList<>(ShaderPackService.packsOrEmpty(scan.get()));
            if (!query.isEmpty()) {
                packs.removeIf(pack -> !pack.displayName().toLowerCase(Locale.ROOT).contains(query)
                        && !pack.fileName().toLowerCase(Locale.ROOT).contains(query));
            }
            if (packs.isEmpty()) {
                if (scan.get().scanned()) {
                    addEntry(new PackEntry("No shader packs found",
                            "Check the folder, then press Rescan.", 0xFF808080, false, null));
                } else {
                    addEntry(new PackEntry("Scan failed",
                            scan.get().error() != null ? scan.get().error() : "unknown error",
                            0xFFB3261E, false, null));
                }
                return;
            }
            for (var pack : packs) {
                addEntry(new PackEntry(pack, model));
            }
            for (var warning : scan.get().warnings()) {
                addEntry(new PackEntry("⚠ " + warning.fileName(), warning.reason(),
                        0xFFFF55, false, null));
            }
        }

        static class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
            private final String title;
            private final String detail;
            private final int titleColor;
            private final boolean selectable;
            private final Runnable onSelect;

            PackEntry(String title, String detail, int titleColor, boolean selectable,
                      Runnable onSelect) {
                this.title = title;
                this.detail = detail;
                this.titleColor = titleColor;
                this.selectable = selectable;
                this.onSelect = onSelect;
            }

            PackEntry(ShaderPackService.DiscoveredPack pack, ShaderPacksCategoryModel model) {
                this(textFor(pack, model), true, () -> model.select(pack.fileName()));
            }

            private PackEntry(EntryText text, boolean selectable, Runnable onSelect) {
                this(text.title(), text.detail(), text.color(), selectable, onSelect);
            }

            private static EntryText textFor(ShaderPackService.DiscoveredPack pack,
                                             ShaderPacksCategoryModel model) {
                boolean selected = model.selectedFile()
                        .map(file -> file.equals(pack.fileName())).orElse(false);
                String meta = pack.fileName() + "  " + (pack.sizeBytes() / 1024) + " KB";
                if (pack.author().isPresent()) {
                    meta += "  by " + pack.author().get();
                }
                if (pack.version().isPresent()) {
                    meta += "  v" + pack.version().get();
                }
                StringBuilder badges = new StringBuilder();
                if (selected) {
                    badges.append("[SELECTED] ");
                }
                if (model.selectedIsActiveInIris()
                        && model.selectedFile().map(f -> f.equals(pack.fileName())).orElse(false)) {
                    badges.append("[ACTIVE IN IRIS] ");
                }
                var mark = model.history().markFor(pack.stableId());
                if (mark.isEmpty()) {
                    badges.append("[UNTESTED]");
                } else if (model.history().isStale(pack.stableId(), pack.contentStamp())) {
                    badges.append("[STALE — RETEST]");
                } else {
                    badges.append(mark.get().passed() ? "[TEST PASSED]" : "[TEST FAILED]");
                }
                int color = selected ? 0xFFFFFF55 : 0xFFFFFFFF;
                return new EntryText((selected ? "> " : "") + pack.displayName(),
                        meta + "  " + badges, color);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                               int entryHeight, int mouseX, int mouseY, boolean hovered,
                               float partialTick) {
                Minecraft minecraft = Minecraft.getInstance();
                graphics.drawString(minecraft.font, title, x + 6, y + 4, titleColor);
                graphics.drawString(minecraft.font,
                        minecraft.font.plainSubstrByWidth(detail, entryWidth - 12),
                        x + 6, y + 18, 0xFF808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (selectable && onSelect != null && button == 0) {
                    onSelect.run();
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(title + ". " + detail);
            }
        }

        private record EntryText(String title, String detail, int color) {
        }
    }
}
