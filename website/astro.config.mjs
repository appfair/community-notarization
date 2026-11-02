import { defineConfig } from 'astro/config';
import { generateResources } from './scripts/generate-resources.mjs';
export default defineConfig({
  site: 'https://appfair.org',
  base: '/community-notarization',
  output: 'static',
  devToolbar: { enabled: false },
  vite: { plugins: [{
    name: 'localized-resources',
    configureServer(server) {
      server.watcher.add(new URL('./resources', import.meta.url).pathname);
      server.watcher.on('change', path => {
        if (path.includes('/resources/')) generateResources();
      });
    },
  }] },
});
