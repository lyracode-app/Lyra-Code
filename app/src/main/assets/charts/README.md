# Bundled chart libraries

These distributables are pinned and included in the APK for offline rendering.

| Asset | Version | SHA-256 |
| --- | --- | --- |
| mermaid.min.js | 11.17.2 | 581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8 |
| echarts.min.js | 6.1.0 | b66b25aeb4df84e33199dc21694014d336d222cbd9deb0e5a7c14bd6aa0d0fd0 |

Original packages:

- https://registry.npmjs.org/mermaid/-/mermaid-11.17.2.tgz (`dist/mermaid.min.js`, `LICENSE`)
- https://registry.npmjs.org/echarts/-/echarts-6.1.0.tgz (`dist/echarts.min.js`, `LICENSE`, `NOTICE`, `licenses/LICENSE-d3`)

The Mermaid distributable retains embedded notices for its bundled dependencies. Do not strip license comments. `renderer.js` is Lyra's own integration, licensed under the repository's AGPL-3.0 license. API references: https://mermaid.js.org/config/usage.html and https://echarts.apache.org/handbook/en/best-practices/canvas-vs-svg/.
