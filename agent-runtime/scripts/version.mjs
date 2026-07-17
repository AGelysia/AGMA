import { readFile } from "node:fs/promises";

const packageUrl = new URL("../package.json", import.meta.url);
const packageJson = JSON.parse(await readFile(packageUrl, "utf8"));

process.stdout.write(`${packageJson.name} ${packageJson.version}\n`);
