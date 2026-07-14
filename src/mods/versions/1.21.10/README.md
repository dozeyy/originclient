# Origin Client — Minecraft 1.21.2 – 1.21.11 build

The Fabric build of Origin Client for the **post-1.21.2 blit-rework family**
(Java 21). One jar (`originclient-1.21.11.jar`) covers the whole range — the
source is byte-identical across 1.21.2 – 1.21.11 — the same way the 1.20 module
covers 1.20 + 1.20.1. Shares the 1.21.1 source; the only version-forced change
is the 1.21.2 `GuiGraphics.blit` rework. `fabric.mod.json` declares
`>=1.21.2- <1.22`. See `PORT-12111.md` for the port map, the per-version ship
gate (shaders + runClient), and the at-home verification steps.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
