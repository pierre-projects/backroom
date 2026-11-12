import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import basicSsl from '@vitejs/plugin-basic-ssl';

// Phones only allow the camera on HTTPS. For testing on a phone over Wi-Fi, run
//   BACKROOM_DEV_HTTPS=1 npm run dev
// and open https://<this computer's LAN address>:5173 on the phone (accept the self-signed certificate once).
const phoneTesting = process.env.BACKROOM_DEV_HTTPS === '1';

export default defineConfig({
  plugins: phoneTesting ? [react(), basicSsl()] : [react()],
  server: {
    host: phoneTesting ? true : '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': process.env.BACKROOM_API_URL ?? 'http://127.0.0.1:8080',
      '/actuator': process.env.BACKROOM_API_URL ?? 'http://127.0.0.1:8080',
    },
  },
});
