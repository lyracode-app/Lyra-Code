# Minty for Lyra Code

Source: https://github.com/somnusochi/minty-codex-pet
Pinned revision: f98e0ac5605b1b2200c7c12e8df3e9b8f12655df
Artwork copyright (c) 2026 Somnusochi, MIT License (see LICENSE).
This is a Lyra Code API 2 adapter, not an official upstream or OpenAI release.

The original 1536x2288 WebP atlas is unchanged. The module draws its 192x208 cells
with Canvas through the documented local resource/JSON/lifecycle interfaces.
Rows 0–8 retain the original frame counts; empty cells are never played.
Rows 9–10 retain all sixteen directional poses. No code from install.sh is run.

Import the ZIP using Lyra desktop pet settings. Select Minty in the pet library.
The pose setting previews all nine animations and sixteen look directions;
auto follows task state, click, drag, and docking events. Speed and roaming are configurable.
