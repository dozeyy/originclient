package com.vulkairis.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Scrollable settings rows: title plus concise description on the left, control on the right.
 * Scrolling, scrollbar and keyboard focus come from the vanilla selection list; rows only lay
 * out one title/description/control triple each.
 */
public class SettingsList extends ObjectSelectionList<SettingsList.Row> {

    private static final int CONTROL_WIDTH = 140;
    private static final int CONTROL_HEIGHT = 20;

    public SettingsList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    /** Replaces all rows (clears focus state with them). */
    public void setRows(List<SettingRow> rows, GuiViewModel model) {
        clearEntries();
        for (SettingRow row : rows) {
            addEntry(new Row(row, createControl(row, model)));
        }
        setScrollAmount(0.0);
    }

    private AbstractWidget createControl(SettingRow row, GuiViewModel model) {
        Tooltip tooltip = row.tooltip().isEmpty() ? null : Tooltip.create(Component.literal(row.tooltip()));
        return switch (row.kind()) {
            case TOGGLE -> new ToggleButton(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                    model.booleanValue(row.key()), tooltip,
                    requested -> model.setBoolean(row.key(), requested));
            case SLIDER -> {
                GuiViewModel.NumericRange range = model.numericRange(row.key());
                yield new ValueSlider(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT, row.title(),
                        range.min(), range.max(), range.step(), model.numericValue(row.key()),
                        value -> model.setNumeric(row.key(), value));
            }
            case ACTION -> Button.builder(Component.literal(row.title()), button -> model.runAction(row.key()))
                    .bounds(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT)
                    .tooltip(tooltip)
                    .build();
            case SELECT -> {
                java.util.List<String> options = model.selectOptions(row.key());
                if (options.isEmpty()) {
                    Button unavailable = Button.builder(
                                    Component.literal("Unavailable"), button -> {
                                    })
                            .bounds(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT)
                            .tooltip(tooltip)
                            .build();
                    unavailable.active = false;
                    yield unavailable;
                }
                String accepted = model.selectValue(row.key());
                if (!options.contains(accepted)) {
                    accepted = options.get(0);
                }
                final String fallback = accepted;
                net.minecraft.client.gui.components.CycleButton<String> select =
                        net.minecraft.client.gui.components.CycleButton.builder(Component::literal)
                                .withValues(model.selectOptions(row.key()))
                                .withInitialValue(accepted)
                                .create(Component.empty(), (button, value) -> {
                                    if (!model.setSelect(row.key(), value)) {
                                        button.setValue(fallback);
                                    }
                                });
                yield select;
            }
            case STATUS -> new StatusBadge(0, 0, CONTROL_WIDTH, CONTROL_HEIGHT,
                    model.statusOf(row.key()), model.statusText(row.key()));
        };
    }

    /** One row: text left, control right. Clicks, drags and keys forward to the control. */
    public static class Row extends ObjectSelectionList.Entry<Row> {
        private final SettingRow row;
        private final AbstractWidget control;

        Row(SettingRow row, AbstractWidget control) {
            this.row = row;
            this.control = control;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                           int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int controlX = x + entryWidth - CONTROL_WIDTH - 4;
            control.setX(controlX);
            control.setY(y + (entryHeight - CONTROL_HEIGHT) / 2);
            control.setWidth(CONTROL_WIDTH);
            control.setHeight(CONTROL_HEIGHT);
            control.render(graphics, mouseX, mouseY, partialTick);

            Minecraft minecraft = Minecraft.getInstance();
            int textX = x + 4;
            int textWidth = entryWidth - CONTROL_WIDTH - 12;
            graphics.drawString(minecraft.font, row.title(), textX, y + 3, 0xFFFFFFFF);
            String description = minecraft.font.plainSubstrByWidth(row.description(), textWidth);
            graphics.drawString(minecraft.font, description, textX, y + 14, 0xFF808080);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (control.isMouseOver(mouseX, mouseY)) {
                control.setFocused(true);
                return control.mouseClicked(mouseX, mouseY, button);
            }
            control.setFocused(false);
            return false;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return control.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button,
                                    double dragX, double dragY) {
            return control.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return control.isFocused() && control.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public Component getNarration() {
            return Component.literal(row.title() + ": " + control.getMessage().getString());
        }
    }
}
