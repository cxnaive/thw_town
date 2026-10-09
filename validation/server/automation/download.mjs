import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

export const artifacts = JSON.parse(
  await readFile(new URL("./artifacts.json", import.meta.url)),
);
export const cache = fileURLToPath(new URL("./cache/", import.meta.url));

function verify(bytes, artifact) {
  if (
    createHash(artifact.algorithm).update(bytes).digest("hex") !== artifact.hash
  )
    throw new Error(`Checksum mismatch: ${artifact.filename}`);
  return bytes;
}

export async function readVerified(directory, artifact) {
  return verify(await readFile(join(directory, artifact.filename)), artifact);
}

export async function downloadArtifacts(server, directory) {
  if (!["folia", "leaf", "folia26_2"].includes(server)) throw new Error("Use folia, leaf or folia26_2");
  await mkdir(directory, { recursive: true });
  for (const artifact of [artifacts[server], artifacts[artifacts[server].mojang ?? "mojang"],
    ...(artifacts[server].plugins ?? []).map(name => artifacts[name])]) {
    try {
      await readVerified(directory, artifact);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      const response = await fetch(artifact.url, {
        headers: {
          "User-Agent": "Dominion-validation/0.1 (local development)",
        },
        signal: AbortSignal.timeout(180_000),
      });
      if (!response.ok)
        throw new Error(
          `Download ${artifact.filename}: HTTP ${response.status}`,
        );
      const bytes = verify(Buffer.from(await response.arrayBuffer()), artifact);
      const target = join(directory, artifact.filename);
      await writeFile(`${target}.part`, bytes);
      await rename(`${target}.part`, target);
    }
    console.log(`Verified ${artifact.filename}`);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({
    options: {
      server: { type: "string", default: "folia" },
      cache: { type: "string", default: cache },
    },
  });
  await downloadArtifacts(values.server, values.cache);
}
