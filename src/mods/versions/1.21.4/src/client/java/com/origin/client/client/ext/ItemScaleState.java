package com.origin.client.client.ext;

// Duck interface: carries the Item Size Customizer's per-item render scale on the
// ItemEntityRenderState. 1.21.4 is the render-state era for item entities — the
// renderer's render() method receives an ItemEntityRenderState, NOT the live
// ItemEntity, so the scale is computed in extractRenderState (which still sees the
// entity) and stashed here for render() to read. See ItemEntityScaleMixin.
//
// MUST live OUTSIDE the mixin package: ItemEntityRenderStateMixin adds this
// interface to the vanilla ItemEntityRenderState, so the game classloader loads
// it directly when that class initializes (during EntityRenderers.<clinit>, run
// from the EntityRenderDispatcher resource-reload). Mixin FORBIDS direct
// references to any class inside a config-owned mixin package
// (IllegalClassLoadError), which — thrown from a reload listener — aborts the
// resource reload and hangs the client on the loading screen forever. Keeping the
// duck interface in this non-mixin package is the standard fix.
public interface ItemScaleState {
	void originclient$setItemScale(float scale);

	float originclient$getItemScale();
}
