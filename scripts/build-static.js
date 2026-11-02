import { cp, mkdir, rm } from "node:fs/promises";
import { join } from "node:path";

const root = process.cwd();
const output = join(root, "public");

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });

// The public site is the Yehaw Android browser product page only.
// Legacy directory/admin scripts are intentionally excluded from deployment.
await cp(join(root, "icons"), join(output, "icons"), { recursive: true });
for (const item of ["index.html", "manifest.json", "sw.js"]) {
  await cp(join(root, item), join(output, item));
}

console.log("Built the Yehaw Android browser product site in public/.");
