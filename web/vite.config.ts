import { defineConfig } from "vitest/config";

export default defineConfig({
  build: {
    outDir: "../app/src/main/assets/web",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "assets/app.js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: (assetInfo) =>
          assetInfo.names.some((name) => name.endsWith(".css"))
            ? "assets/app.css"
            : "assets/[name][extname]",
      },
    },
  },
  test: {
    environment: "jsdom",
    clearMocks: true,
  },
});
