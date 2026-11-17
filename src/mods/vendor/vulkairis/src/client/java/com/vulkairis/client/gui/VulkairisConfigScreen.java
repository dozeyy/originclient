package com.vulkairis.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Vulkairis settings root: title bar, left category rail, scrollable content, Reset/Done
 * footer. Minecraft-native look (dark panels, rectangular buttons, compact labels) and
 * vanilla focus/scroll/keyboard behavior throughout.
 *
 * <p>Done saves and applies pending values, then returns to the parent; failures keep the
 * screen open with a notice. Escape closes without applying. Reset asks for confirmation
 * (two-step) and never touches shader packs, worlds, or unrelated mod configs.</p>
 */
public class VulkairisConfigScreen extends Screen {

    private static final int RAIL_WIDTH = 120;
    private static final int MARGIN = 10;
    private static final int TOP_BAR = 28;
    private static final int FOOTER = 28;

    private final Screen parent;
    private final java.util.function.Function<GuiCategory, GuiViewModel> models;
    private GuiCategory category = GuiCategory.GENERAL;
    private SettingsList list;
    private final Map<GuiCategory, Button> railButtons = new EnumMap<>(GuiCategory.class);
    private Button resetButton;
    private boolean resetArmed;
    private String notice = "";
    private long seenModelVersion = -1L;

    public VulkairisConfigScreen(Screen parent, GuiViewModel model) {
        this(parent, ignored -> model);
    }

    /**
     * @param parent parent screen
     * @param models per-category model provider (real models where services exist)
     */
    public VulkairisConfigScreen(Screen parent,
                                 java.util.function.Function<GuiCategory, GuiViewModel> models) {
        super(Component.literal("Vulkairis"));
        this.parent = parent;
        this.models = models != null ? models : category -> new DevGuiViewModel();
    }

    private GuiViewModel model() {
        try {
            GuiViewModel resolved = models.apply(category);
            return resolved != null ? resolved : new DevGuiViewModel();
        } catch (Throwable t) {
            return new DevGuiViewModel();
        }
    }

    /** @return the currently displayed category (tests and navigation use) */
    public GuiCategory category() {
        return category;
    }

    @Override
    protected void init() {
        clearWidgets();
        railButtons.clear();

        int railX = MARGIN;
        int buttonWidth = RAIL_WIDTH - 8;
        int buttonY = TOP_BAR + 8;
        for (GuiCategory value : GuiCategory.values()) {
            GuiCategory target = value;
            Button rail = Button.builder(Component.literal(target.title()),
                            button -> showCategory(target))
                    .bounds(railX + 4, buttonY, buttonWidth, 18)
                    .build();
            railButtons.put(target, rail);
            addRenderableWidget(rail);
            buttonY += 22;
        }

        int listX = MARGIN + RAIL_WIDTH;
        int listWidth = Math.max(200, this.width - listX - MARGIN);
        list = new SettingsList(minecraft, listWidth, this.height - TOP_BAR - FOOTER - MARGIN,
                TOP_BAR + 4, 40);
        list.setX(listX);
        refreshRows();
        addRenderableWidget(list);

        int footerY = this.height - FOOTER + 6;
        resetButton = Button.builder(Component.literal("Reset"), button -> onResetPressed())
                .bounds(listX, footerY, 100, 20)
                .build();
        addRenderableWidget(resetButton);
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onDonePressed())
                .bounds(listX + listWidth - 100, footerY, 100, 20)
                .build());
        updateFooterNotice();
    }

    private void showCategory(GuiCategory next) {
        this.category = next;
        this.resetArmed = false;
        resetButton.setMessage(Component.literal("Reset"));
        for (Map.Entry<GuiCategory, Button> entry : railButtons.entrySet()) {
            entry.getValue().setMessage(Component.literal(entry.getKey().title()));
        }
        refreshRows();
    }

    private void refreshRows() {
        if (list != null) {
            list.setRows(model().rowsFor(category), model());
        }
    }

    private void onResetPressed() {
        if (!resetArmed) {
            resetArmed = true;
            resetButton.setMessage(Component.literal("Confirm Reset"));
            notice = "Reset '" + category.title() + "' to defaults? Press again to confirm.";
            return;
        }
        resetArmed = false;
        resetButton.setMessage(Component.literal("Reset"));
        model().resetCategory(category);
        refreshRows();
        notice = "Category reset. Shader packs, worlds and other mods are untouched.";
    }

    private void onDonePressed() {
        GuiViewModel.ApplyResult result = model().saveAndApply();
        if (!result.failures().isEmpty()) {
            notice = "Could not apply: " + String.join("; ", result.failures());
            return;
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void updateFooterNotice() {
        String footer = model().footerNotice();
        if (!footer.isEmpty()) {
            notice = footer;
        }
    }

    /**
     * Refreshes rows when the model version moves (async completions, external changes)
     * without stealing focus or rebuilding on every frame.
     */
    @Override
    public void tick() {
        super.tick();
        try {
            long current = model().version();
            if (current != seenModelVersion) {
                seenModelVersion = current;
                refreshRows();
                updateFooterNotice();
            }
            String navigation = model().consumeNavigation();
            if ("browser".equals(navigation) && model() instanceof ShaderPacksCategoryModel packs) {
                Minecraft.getInstance().setScreen(new ShaderPackBrowserScreen(this, packs));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Vanilla background (blur + darkening) so the screen matches native menus.
        renderBackground(graphics, mouseX, mouseY, partialTick);
        // Subtle panel behind the category rail and content area for a clean, minimalist look.
        int railPanelX = MARGIN - 2;
        int railPanelY = TOP_BAR - 2;
        int railPanelW = RAIL_WIDTH + 4;
        int railPanelH = this.height - TOP_BAR - FOOTER + 4;
        graphics.fill(railPanelX, railPanelY, railPanelX + railPanelW, railPanelY + railPanelH, 0x60000000);
        int contentX = MARGIN + RAIL_WIDTH;
        int contentW = this.width - contentX - MARGIN;
        graphics.fill(contentX - 2, railPanelY, contentX + contentW + 2, railPanelY + railPanelH, 0x40000000);
        // Vanilla-style title: centered, shadowed.
        graphics.drawCenteredString(font, getTitle(), this.width / 2, 8, 0xFFFFFFFF);
        // Subtitle showing the active category, vanilla gray.
        graphics.drawCenteredString(font, category.title(), this.width / 2, 20, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!notice.isEmpty()) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(notice, this.width - 40),
                    this.width / 2, this.height - FOOTER - 12, 0xFFFF55);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
