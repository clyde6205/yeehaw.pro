import test from "node:test";
import assert from "node:assert/strict";
import sharp from "sharp";
import { optimizeImage } from "../api/_browser/image.js";

test("converts source raster images into bounded WebP output", async () => {
  const source = await sharp({
    create: { width: 64, height: 48, channels: 3, background: { r: 80, g: 140, b: 200 } }
  }).png().toBuffer();
  const output = await optimizeImage(source);
  const metadata = await sharp(output).metadata();
  assert.equal(metadata.format, "webp");
  assert.equal(metadata.width, 64);
  assert.equal(metadata.height, 48);
  assert.ok(output.length > 0);
});
