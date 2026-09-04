package com.origin.client.client.render;

/**
 * THE one vertical layout of the Origin main menu, shared by TitleScreenMixin
 * (which places the nav buttons + cards) and OriginScreenRenderer (which draws
 * the wordmark). Both used to run their own copy of "the same" formula, and
 * the copies drifted the moment the nav had to shift up on a short window —
 * the wordmark stayed put and landed on top of Singleplayer at most sizes.
 * Now there is exactly one block, laid out top-down, that every part reads.
 *
 * The block, from the top: wordmark (ink height {@link #inkH}) → gap → nav list
 * ({@link #navCount} buttons, 20 px each, a small group gap before the last
 * two: Options / Quit). It is fitted between the account chip and the
 * language/accessibility icon row: when it doesn't fit, the wordmark shrinks
 * first (down to a floor), then the nav tightens. What's left over is split
 * slightly above centre so the composition sits like the mockup.
 *
 * The right-hand cards hang off the same block: the stats card tops out level
 * with the wordmark, CONTINUE sits under it; each is shown only when it fits
 * without touching the nav.
 *
 * All values are GUI-scaled pixels for the given screen size.
 */
public final class TitleLayout {
	/** Number of nav buttons — set by TitleScreenMixin once it has laid them out. */
	public static volatile int navCount = 6;
	/** Wordmark ink aspect (width / height) — set by OriginScreenRenderer when the texture loads. */
	public static volatile double inkAspect = 5.5;
	/** CONTINUE card entries (world / server) currently known — sizes the card. */
	public static volatile int contEntries = 1;

	public final int left, navW, navTop, navStep, navGroupGap, iconRowY;
	public final double inkH, markCenterX, markCenterY;
	public final int cardX, cardY, cardW, cardH, contY, contH;
	public final boolean card, cont;

	private TitleLayout(int left, int navW, int navTop, int navStep, int navGroupGap, int iconRowY,
						double inkH, double markCenterX, double markCenterY,
						int cardX, int cardY, int cardW, int cardH, boolean card, int contY, int contH, boolean cont) {
		this.left = left;
		this.navW = navW;
		this.navTop = navTop;
		this.navStep = navStep;
		this.navGroupGap = navGroupGap;
		this.iconRowY = iconRowY;
		this.inkH = inkH;
		this.markCenterX = markCenterX;
		this.markCenterY = markCenterY;
		this.cardX = cardX;
		this.cardY = cardY;
		this.cardW = cardW;
		this.cardH = cardH;
		this.card = card;
		this.contY = contY;
		this.contH = contH;
		this.cont = cont;
	}

	public static TitleLayout of(int sw, int sh) {
		int n = Math.max(1, navCount);
		double aspect = Math.max(1.0, inkAspect);
		int left = Math.max(24, (int) Math.round(sw * 0.08));
		int navW = Math.max(130, Math.min(180, sw / 4));
		// Vertical room: below the account chip (drawn at ~3% + 30 px), above the
		// icon row that sits 34 px off the bottom.
		int chipBottom = Math.max(10, (int) Math.round(sw * 0.03)) + 30;
		int topMin = chipBottom + 6;
		int iconRowY = sh - 30;
		// The language/accessibility icons sit bottom-RIGHT (not under the nav),
		// so the nav block may run down to the bottom margin.
		int bottomLimit = sh - 12;
		int avail = Math.max(60, bottomLimit - topMin);

		int step = 24, groupGap = 8, markGap = 20;
		double inkH = Math.min(sh * 0.16, (sw * 0.44) / aspect);
		double inkFloor = Math.min(inkH, Math.max(18.0, sh * 0.09));
		int navH = n * step - 4 + groupGap;
		if (inkH + markGap + navH > avail) {
			inkH = Math.max(inkFloor, avail - markGap - navH);
		}
		if (inkH + markGap + navH > avail) {          // still tight: tighten the nav
			step = 22;
			groupGap = 4;
			markGap = 12;
			navH = n * step - 2 + groupGap;
			inkH = Math.max(inkFloor, Math.min(inkH, avail - markGap - navH));
		}
		double blockH = inkH + markGap + navH;
		double blockTop = topMin + Math.max(0.0, (avail - blockH) * 0.40);
		double markCenterY = blockTop + inkH / 2.0;
		double markCenterX = left + (inkH * aspect) / 2.0;
		int navTop = (int) Math.round(blockTop + inkH + markGap);

		int cardW = Math.max(160, Math.min(240, (int) Math.round(sw * 0.28)));
		int cardX = sw - left - cardW;
		int cardY = (int) Math.round(blockTop);
		int cardH = 102;
		// Cards share the right column with the icon row, so they stop above it.
		int cardLimit = iconRowY - 8;
		boolean card = cardX >= left + navW + 24 && cardY + cardH <= cardLimit;
		int contY = cardY + cardH + 10, contH = 22 + Math.max(1, contEntries) * 30 + 4;
		boolean cont = card && contY + contH <= cardLimit;
		return new TitleLayout(left, navW, navTop, step, groupGap, iconRowY, inkH, markCenterX, markCenterY,
				cardX, cardY, cardW, cardH, card, contY, contH, cont);
	}
}
