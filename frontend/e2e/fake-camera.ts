import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { prepareZXingModule, writeBarcode } from 'zxing-wasm/writer';

/**
 * Writes a Y4M video that shows one QR code, for Chromium's fake camera
 * (--use-file-for-fake-video-capture). The WebAssembly encoder is read from node_modules; nothing is downloaded.
 */
export async function writeQrVideo(path: string, text: string) {
  const require = createRequire(import.meta.url);
  const wasm = readFileSync(require.resolve('zxing-wasm/writer/zxing_writer.wasm'));
  await prepareZXingModule({ overrides: { wasmBinary: wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength) }, fireImmediately: true });
  const { symbol, error } = await writeBarcode(text, { format: 'QRCode' });
  if (error) throw new Error(error);

  const width = 640, height = 480, scale = 8;
  const size = symbol.width * scale;
  const left = Math.floor((width - size) / 2), top = Math.floor((height - size) / 2);
  const luma = new Uint8Array(width * height).fill(235); // white page
  for (let y = 0; y < symbol.height; y++) {
    for (let x = 0; x < symbol.width; x++) {
      if (symbol.data[y * symbol.width + x] !== 0) continue; // 0 = dark module
      for (let dy = 0; dy < scale; dy++) luma.fill(16, (top + y * scale + dy) * width + left + x * scale, (top + y * scale + dy) * width + left + (x + 1) * scale);
    }
  }
  const chroma = new Uint8Array((width / 2) * (height / 2)).fill(128);
  const frame = Buffer.concat([Buffer.from('FRAME\n'), luma, chroma, chroma]);
  const header = Buffer.from(`YUV4MPEG2 W${width} H${height} F10:1 Ip A1:1 C420jpeg\n`);
  writeFileSync(path, Buffer.concat([header, ...Array.from({ length: 10 }, () => frame)]));
}
