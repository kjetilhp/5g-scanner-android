import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { Resvg } from "@resvg/resvg-js";

const root = process.cwd();
const sourcePath = path.join(root, "assets", "icon", "source.svg");
const sourceSvg = await readFile(sourcePath, "utf8");

const webIcons = [
  ["favicon-16.png", 16],
  ["favicon-32.png", 32],
  ["favicon-48.png", 48],
];

const generatedDir = path.join(root, "assets", "icon", "generated");

async function renderPng(svg, size, outPath) {
  await mkdir(path.dirname(outPath), { recursive: true });
  const png = new Resvg(svg, {
    fitTo: {
      mode: "width",
      value: size,
    },
  }).render().asPng();
  await writeFile(outPath, png);
  console.log(`Generated ${path.relative(root, outPath)} (${size}x${size})`);
}

await mkdir(generatedDir, { recursive: true });

for (const [fileName, size] of webIcons) {
  await renderPng(sourceSvg, size, path.join(generatedDir, fileName));
}

console.log("Favicon generation complete.");
