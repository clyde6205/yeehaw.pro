import sharp from "sharp";

export async function optimizeImage(input) {
  return sharp(input, { limitInputPixels: 40000000, animated: false })
    .rotate()
    .resize({ width: 1280, height: 1280, fit: "inside", withoutEnlargement: true })
    .webp({ quality: 72, effort: 4 })
    .toBuffer();
}
