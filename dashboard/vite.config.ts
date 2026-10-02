import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 5174, not Vite's 5173: the DSTNS operator UI already runs there, and the two
// are meant to be open side by side. /api goes to `./bus serve`.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { "/api": process.env.ROUTE_API ?? "http://127.0.0.1:8080" },
  },
});
