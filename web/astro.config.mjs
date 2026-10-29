// castbay.weenas.com: static pages, built into dist/, which Cloudflare builds and publishes
// (see wrangler.jsonc).
import { defineConfig } from 'astro/config';
import { satteri } from '@astrojs/markdown-satteri';
import latestApk from './integrations/latest-apk.mjs';
import latestDesktop from './integrations/latest-desktop.mjs';

export default defineConfig({
  site: 'https://castbay.weenas.com',
  // guide.md becomes guide.html (served at /guide) and zh/index.astro zh/index.html, as the
  // hand-written site had them.
  build: { format: 'preserve' },
  trailingSlash: 'ignore',
  // Keep the HTML readable, and quotes as written (Chinese text uses straight quotes).
  compressHTML: false,
  markdown: { processor: satteri({ features: { smartPunctuation: false } }) },
  integrations: [latestApk(), latestDesktop()],
});
