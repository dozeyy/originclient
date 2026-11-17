package com.vulkairis.client.gui;

/**
 * Model composition for the settings screen: real models where services exist, explicit
 * development scaffolding everywhere else. Each phase replaces one dev entry with its real
 * model; the dev model must never back a released category.
 */
public final class VulkairisGuiModels {

    private VulkairisGuiModels() {
    }

    /** @return the model backing a category right now (dev scaffolding where unreal) */
    public static GuiViewModel forCategory(GuiCategory category) {
        GuiViewModel model = models().get(category);
        return model != null ? model : new DevGuiViewModel();
    }

    private static java.util.Map<GuiCategory, GuiViewModel> models() {
        return Holder.MODELS;
    }

    private static final class Holder {
        static final java.util.Map<GuiCategory, GuiViewModel> MODELS = modelMap();

        private static java.util.Map<GuiCategory, GuiViewModel> modelMap() {
            java.util.Map<GuiCategory, GuiViewModel> map =
                    new java.util.EnumMap<>(GuiCategory.class);
            map.put(GuiCategory.GENERAL, new GeneralCategoryModel());
            map.put(GuiCategory.RENDERING, new RenderingCategoryModel());
            map.put(GuiCategory.SHADER_PACKS, new ShaderPacksCategoryModel());
            map.put(GuiCategory.COMPATIBILITY, new CompatibilityCategoryModel());
            map.put(GuiCategory.DIAGNOSTICS, new DiagnosticsCategoryModel());
            map.put(GuiCategory.ADVANCED, new AdvancedCategoryModel());
            return map;
        }
    }

    /** @return whether the category is backed by a real model (not development scaffolding) */
    public static boolean isReal(GuiCategory category) {
        return category == GuiCategory.GENERAL || category == GuiCategory.RENDERING
                || category == GuiCategory.SHADER_PACKS || category == GuiCategory.COMPATIBILITY
                || category == GuiCategory.DIAGNOSTICS || category == GuiCategory.ADVANCED;
    }
}
