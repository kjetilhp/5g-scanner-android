import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { Resvg } from "@resvg/resvg-js";

const root = process.cwd();
const sourcePath = path.join(root, "assets", "icon", "source.svg");
const sourceSvg = await readFile(sourcePath, "utf8");
const foregroundSvg = sourceSvg.replace(
  /<g id="background">[\s\S]*?<\/g>\s*/m,
  "",
);

const androidDensities = [
  ["mipmap-mdpi", 48, 108],
  ["mipmap-hdpi", 72, 162],
  ["mipmap-xhdpi", 96, 216],
  ["mipmap-xxhdpi", 144, 324],
  ["mipmap-xxxhdpi", 192, 432],
];

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

for (const [dir, legacySize, foregroundSize] of androidDensities) {
  const outDir = path.join(root, "app", "src", "main", "res", dir);
  await renderPng(sourceSvg, legacySize, path.join(outDir, "ic_launcher.png"));
  await renderPng(sourceSvg, legacySize, path.join(outDir, "ic_launcher_round.png"));
  await renderPng(
    foregroundSvg,
    foregroundSize,
    path.join(outDir, "ic_launcher_foreground.png"),
  );
}

for (const [fileName, size] of webIcons) {
  await renderPng(sourceSvg, size, path.join(generatedDir, fileName));
}

console.log("Icon generation complete.");
