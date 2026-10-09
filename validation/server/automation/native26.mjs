import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import {
  mkdtemp,
  mkdir,
  readFile,
  writeFile,
  copyFile,
  cp,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { parseArgs } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import { artifacts, cache, readVerified } from "./download.mjs";
import {
  assertPortFree,
  managedProcess,
  until,
  logIssues,
} from "./harness.mjs";

const { values: options } = parseArgs({
  options: {
    "accept-eula": { type: "boolean" },
    port: { type: "string", default: "25687" },
    libraries: { type: "string" },
    jar: { type: "string" },
    cache: { type: "string", default: cache },
  },
});
assert(
  options["accept-eula"],
  "Pass --accept-eula after reading https://aka.ms/MinecraftEULA",
);
const port = Number(options.port);
await assertPortFree(port);
const root = fileURLToPath(new URL("../../../", import.meta.url));
const build = await readFile(join(root, "build.gradle.kts"), "utf8");
const pluginVersion = /^version = "([^"$]+)-\$suffixes"/m.exec(build)[1];
const jar =
  options.jar ??
  join(root, "build/libs", `Dominion-${pluginVersion}-release-full.jar`);
const fixtureJar = join(
  root,
  "validation/server/fixture/build/libs/dominion-upgrade-fixture.jar",
);
const clientJar = join(
  root,
  "validation/client26_2/build/install/dominion-native26-client/lib/dominion-native26-client.jar",
);
const dir = await mkdtemp(join(tmpdir(), "dominion-native26-"));
await mkdir(join(dir, "plugins/Dominion"), { recursive: true });
await mkdir(join(dir, "cache"));
await mkdir(join(dir, "config"));
if (options.libraries)
  await cp(options.libraries, join(dir, "libraries"), { recursive: true });
await writeFile(
  join(dir, ".dominion-upgrade-validation"),
  "isolated local QA\n",
);
await writeFile(
  join(dir, "server.jar"),
  await readVerified(options.cache, artifacts.folia26_2),
);
await writeFile(
  join(dir, "cache", artifacts.mojang26_2.filename),
  await readVerified(options.cache, artifacts.mojang26_2),
);
await copyFile(jar, join(dir, "plugins/Dominion.jar"));
await copyFile(fixtureJar, join(dir, "plugins/fixture.jar"));
await writeFile(
  join(dir, "plugins/Dominion/config.yml"),
  "version: 3\ndefault-ui-type: BY_PLAYER\ntown:\n  founding-cost: 0\n",
);
await writeFile(
  join(dir, "config/paper-global.yml"),
  "threaded-regions:\n  threads: 2\n",
);
await writeFile(
  join(dir, "spigot.yml"),
  'commands:\n  enable-spam-exclusions: true\n  spam-exclusions: ["/dom", "/town"]\n',
);
await writeFile(join(dir, "eula.txt"), "eula=true\n");
await writeFile(
  join(dir, "server.properties"),
  `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}]}\ngenerate-structures=false\ndifficulty=normal\nallow-flight=true\n`,
);
await writeFile(
  join(dir, "environment.json"),
  JSON.stringify(
    {
      server: artifacts.folia26_2.version,
      serverHash: artifacts.folia26_2.hash,
      pluginVersion,
      pluginHash: createHash("sha256")
        .update(await readFile(jar))
        .digest("hex"),
      fixtureHash: createHash("sha256")
        .update(await readFile(fixtureJar))
        .digest("hex"),
      clientHash: createHash("sha256")
        .update(await readFile(clientJar))
        .digest("hex"),
      client: "MCProtocolLib 26.2-20260907.143312-19",
      protocol: 776,
      protocolTranslation: false,
      port,
    },
    null,
    2,
  ),
);
console.log(`RUN_DIR=${dir}`);
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin/java")
  : "/usr/lib/jvm/java-25-openjdk-amd64/bin/java";
const server = managedProcess(
  java,
  [
    "-Xms512M",
    "-Xmx2G",
    "-XX:ActiveProcessorCount=4",
    "-jar",
    "server.jar",
    "--nogui",
  ],
  { cwd: dir, log: join(dir, "console.log") },
);
const results = [],
  trace = [],
  clients = [];
let failure,
  token = 0;
function client(name) {
  const child = spawn(
    join(
      root,
      "validation/client26_2/build/install/dominion-native26-client/bin/dominion-native26-client",
    ),
    [String(port), name],
    {
      env: { ...process.env, JAVA_HOME: "/usr/lib/jvm/java-25-openjdk-amd64" },
    },
  );
  const state = {
    child,
    events: [],
    error: null,
    position: null,
    window: null,
    entities: new Map(),
    metadata: [],
    closed: false,
  };
  clients.push(state);
  let buffer = "";
  child.stdout.on("data", (bytes) => {
    buffer += bytes;
    let at;
    while ((at = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      try {
        const e = JSON.parse(line);
        trace.push({ name, ...e });
        state.events.push(e);
        if (e.type === "position") state.position = e.value;
        if (e.type === "window") state.window = { ...e.value, items: [] };
        if (e.type === "content" && state.window?.id === e.value.id)
          state.window.items = e.value.items;
        if (e.type === "slot" && state.window?.id === e.value.id)
          state.window.items[e.value.slot] = e.value.item;
        if (e.type === "close") state.window = null;
        if (e.type === "entity") state.entities.set(e.value.id, e.value);
        if (e.type === "metadata") state.metadata.push(e.value);
        if (e.type === "error" || e.type === "disconnect")
          state.error = e.value;
      } catch (error) {
        state.error = `Invalid native client output: ${line}: ${error.message}`;
      }
    }
  });
  child.stderr.on("data", (bytes) =>
    writeFile(join(dir, `${name}-stderr.log`), bytes, { flag: "a" }),
  );
  child.on("error", (error) => (state.error = error.message));
  child.on("close", () => (state.closed = true));
  state.check = () => {
    server.check();
    if (state.error) throw new Error(JSON.stringify(state.error));
    if (state.closed) throw new Error("Native client exited");
  };
  state.send = async (op, args = {}) => {
    state.check();
    const id = ++token;
    child.stdin.write(JSON.stringify({ id, op, ...args }) + "\n");
    await until("native client ack", () => {
      state.check();
      return state.events.some((e) => e.type === "ack" && e.value === id);
    });
  };
  return state;
}
async function scenario(name, action) {
  const start = performance.now();
  await action();
  results.push({
    name,
    status: "PASS",
    elapsedMs: Math.round(performance.now() - start),
  });
  console.log(`PASS ${name}`);
}
let bot;
async function qa(op) {
  const id = String(++token);
  server.command(`upgradeqa ${id} NativeQA ${op}`);
  let value;
  await until(`fixture ${op}`, async () => {
    bot.check();
    try {
      value = JSON.parse(await readFile(join(dir, `qa-${id}.json`), "utf8"));
      return true;
    } catch (e) {
      if (e.code === "ENOENT") return false;
      throw e;
    }
  });
  assert(!value.error, value.error);
  if (op.startsWith("flag ")) {
    const [, flag, expected] = op.split(" ");
    await until(
      "flag published to protection cache",
      async () => {
        const state = await qa(`cache ${flag}`);
        return (
          state.claim === "UpgradeClaim" &&
          state.value === (expected === "true")
        );
      },
      { timeout: 10000, interval: 150 },
    );
  }
  return value.value;
}
async function close() {
  await bot.send("close");
  await delay(100);
}
async function window(command, title) {
  await close();
  await bot.send("command", { command });
  await until(`window ${title}`, () => {
    bot.check();
    return bot.window?.title.includes(title) && bot.window.items.some(Boolean);
  });
  await delay(200);
}
async function chat(command, text) {
  await close();
  const begin = bot.events.length;
  await bot.send("command", { command });
  await until(`chat ${text}`, () => {
    bot.check();
    return bot.events
      .slice(begin)
      .some((e) => e.type === "chat" && e.value.includes(text));
  });
  await delay(250);
}
async function allowedContainer(
  material,
  allowed = true,
  coords = { x: 100, y: 80, z: 102 },
) {
  await close();
  await qa(`block ${material}`);
  await qa("held AIR");
  const begin = bot.events.length;
  await bot.send("block", coords);
  if (allowed) {
    await until("opened container", () => {
      bot.check();
      return bot.window && bot.window.items.some(Boolean);
    });
    assert.equal(bot.window.items.length >= 27, true);
    await bot.send("click", { slot: 0 });
    await delay(200);
    const state = await qa("container-snapshot");
    assert.equal(state.contents, 0, "Container item is actually picked up");
    assert.equal(state.cursor, 7);
  } else {
    await delay(450);
    bot.check();
    assert.equal(bot.window, null, `${material} must deny opening`);
    assert(
      bot.events
        .slice(begin)
        .some((e) => e.type === "chat" && e.value.includes("不允许")),
      "Permission denial feedback",
    );
  }
}
try {
  await until(
    "server startup",
    () => {
      server.check();
      return server.state.tail.includes("Done (");
    },
    { timeout: 180000 },
  );
  assert(
    server.state.tail.includes("Plugin Enabled!") ||
      server.state.tail.includes("启动完成！"),
    "Dominion enabled",
  );
  bot = client("NativeQA");
  const owner = client("UpgradeOwner");
  await until(
    "both native 26.2 clients spawn",
    () => {
      bot.check();
      owner.check();
      return bot.position && owner.position;
    },
    { timeout: 60000 },
  );
  await delay(5000);
  await scenario(
    "native 26.2 protocol 776 login without translation",
    async () => {
      for (const c of [bot, owner])
        assert(
          c.events.some(
            (e) =>
              e.type === "codec" &&
              e.value.protocol === 776 &&
              e.value.version === "26.2",
          ),
        );
    },
  );
  await scenario(
    "native GUI handbook navigation and create a town",
    async () => {
      await window("/town help", "小镇手册");
      const index = bot.window.items.findIndex((i) => i.includes("开始玩小镇"));
      assert(index >= 0);
      await bot.send("click", { slot: index });
      await until("chapter GUI", () =>
        bot.window?.title.includes("开始玩小镇"),
      );
      await chat("/town create NativeTown", "NativeTown");
      await window("/town", "小镇：");
      if (!bot.window.items.some((i) => i.includes("成员权限管理"))) {
        const next = bot.window.items.findIndex((i) =>
          i.includes("content='>>>'"),
        );
        assert(next >= 0);
        await bot.send("click", { slot: next });
        await until("member permissions on next town page", () =>
          bot.window?.items.some((i) => i.includes("成员权限管理")),
        );
      }
    },
  );
  await scenario(
    "native 26.2 TUI commands and clickable chapter links",
    async () => {
      await close();
      await bot.send("command", { command: "/dom switch_ui TUI" });
      await delay(500);
      const begin = bot.events.length;
      await chat("/town help", "小镇手册");
      const chapter = bot.events
        .slice(begin)
        .filter((e) => e.type === "chat")
        .flatMap((e) => {
          const commands = [];
          const walk = (node) => {
            if (!node || typeof node !== "object") return;
            const event = node.click_event ?? node.clickEvent;
            if (event && JSON.stringify(node).includes("权限怎么继承"))
              commands.push(event.command ?? event.value);
            Object.values(node).forEach((v) => {
              if (Array.isArray(v)) v.forEach(walk);
              else if (v && typeof v === "object") walk(v);
            });
          };
          walk(JSON.parse(e.value));
          return commands;
        })
        .filter(Boolean);
      assert(chapter.length);
      await chat(chapter.at(-1), "权限怎么继承");
      await bot.send("command", { command: "/dom switch_ui CUI" });
      await delay(300);
    },
  );
  await close();
  server.command("tp NativeQA 100.5 80 100.5");
  await until(
    "fixture teleport",
    () => bot.position?.x === 100.5 && bot.position?.y === 80,
  );
  await qa("floor");
  await qa("claim");
  await until(
    "claim published",
    async () => (await qa("cache container")).claim === "UpgradeClaim",
    { timeout: 10000, interval: 150 },
  );
  await scenario(
    "native block and item display packets include 26.2 sulfur",
    async () => {
      const ids = await qa("display");
      await until(
        "two native displays decoded",
        () =>
          bot.entities.get(ids.block)?.kind === "BLOCK_DISPLAY" &&
          bot.entities.get(ids.item)?.kind === "ITEM_DISPLAY",
      );
      await until("display metadata", () =>
        [ids.block, ids.item].every((id) =>
          bot.metadata.some((m) => m.id === id),
        ),
      );
      await qa("destroy");
    },
  );
  await scenario(
    "native placement and breaking protect new sulfur and cinnabar blocks",
    async () => {
      server.command("gamemode creative NativeQA");
      await delay(250);
      for (const material of ["SULFUR", "CINNABAR"]) {
        await qa(`block ${material}`);
        await qa("held AIR");
        await qa("flag break false");
        await bot.send("break", { x: 100, y: 80, z: 102 });
        await delay(250);
        assert.equal((await qa("snapshot")).block, material);
        await qa("flag break true");
        await bot.send("break", { x: 100, y: 80, z: 102 });
        await delay(250);
        assert.equal((await qa("snapshot")).block, "AIR");
        await qa(`held ${material}`);
        await qa("flag place false");
        await bot.send("block", { x: 100, y: 79, z: 102 });
        await delay(250);
        assert.equal((await qa("snapshot")).block, "AIR");
        await qa("flag place true");
        await bot.send("block", { x: 100, y: 79, z: 102 });
        await delay(250);
        assert.equal((await qa("snapshot")).block, material);
      }
      server.command("gamemode survival NativeQA");
      await delay(250);
    },
  );
  await qa("flag container false");
  for (const material of [
    "CHEST",
    "TRAPPED_CHEST",
    "BARREL",
    "SHULKER_BOX",
    "WHITE_SHULKER_BOX",
    "COPPER_CHEST",
    "EXPOSED_COPPER_CHEST",
    "WEATHERED_COPPER_CHEST",
    "OXIDIZED_COPPER_CHEST",
    "WAXED_COPPER_CHEST",
    "WAXED_EXPOSED_COPPER_CHEST",
    "WAXED_WEATHERED_COPPER_CHEST",
    "WAXED_OXIDIZED_COPPER_CHEST",
  ])
    await scenario(`deny guest ${material}`, () =>
      allowedContainer(material, false),
    );
  await qa("flag container true");
  for (const material of [
    "CHEST",
    "TRAPPED_CHEST",
    "BARREL",
    "SHULKER_BOX",
    "WHITE_SHULKER_BOX",
    "COPPER_CHEST",
    "EXPOSED_COPPER_CHEST",
    "WEATHERED_COPPER_CHEST",
    "OXIDIZED_COPPER_CHEST",
    "WAXED_COPPER_CHEST",
    "WAXED_EXPOSED_COPPER_CHEST",
    "WAXED_WEATHERED_COPPER_CHEST",
    "WAXED_OXIDIZED_COPPER_CHEST",
  ])
    await scenario(`allow guest ${material} and inventory click`, () =>
      allowedContainer(material),
    );
  await scenario(
    "both chest halves obey permissions across a claim boundary",
    async () => {
      await close();
      await qa("flag container false");
      assert.equal(await qa("double"), 54);
      await bot.send("block", { x: 100, y: 80, z: 101 });
      await delay(500);
      assert.equal(bot.window, null);
      await qa("flag container true");
      await bot.send("block", { x: 100, y: 80, z: 101 });
      await until(
        "allowed double chest",
        () => bot.window?.items.length === 90,
      );
      await close();
    },
  );
  for (const material of ["OAK_SHELF", "BAMBOO_SHELF", "PALE_OAK_SHELF"])
    await scenario(`shelf ${material} obeys container access`, async () => {
      await qa(`shelf ${material}`);
      await qa("held AIR");
      await qa("flag container false");
      await bot.send("block", { x: 100, y: 80, z: 102, face: "NORTH" });
      await delay(350);
      assert.equal(await qa("shelf-snapshot"), 7);
      await qa("flag container true");
      await bot.send("block", { x: 100, y: 80, z: 102, face: "NORTH" });
      await delay(400);
      assert.equal(await qa("shelf-snapshot"), 0);
    });
  await scenario("sulfur cube block absorption deny then allow", async () => {
    const cube = await qa("spawn SULFUR_CUBE");
    await qa("held STONE");
    await qa("flag container false");
    await bot.send("interact", { entity: cube.id });
    await delay(350);
    assert.equal((await qa("entity SULFUR_CUBE")).body, "AIR");
    assert.equal((await qa("snapshot")).amount, 8);
    await qa("flag container true");
    await bot.send("interact", { entity: cube.id });
    await delay(400);
    assert.equal((await qa("entity SULFUR_CUBE")).body, "STONE");
    assert.equal((await qa("snapshot")).amount, 7);
  });
  await scenario("sulfur cube extraction obeys shear permissions", async () => {
    const cube = await qa("entity SULFUR_CUBE");
    await qa("held SHEARS");
    await qa("flag shear false");
    await bot.send("interact", { entity: cube.id });
    await delay(350);
    assert.equal((await qa("entity SULFUR_CUBE")).body, "STONE");
    await qa("flag shear true");
    await bot.send("interact", { entity: cube.id });
    await delay(400);
    assert.equal((await qa("entity SULFUR_CUBE")).body, "AIR");
  });
  await scenario(
    "sulfur cube bucket capture obeys break permissions",
    async () => {
      const cube = await qa("spawn SULFUR_CUBE");
      await qa("held BUCKET");
      await qa("flag break false");
      await bot.send("interact", { entity: cube.id });
      await delay(350);
      assert((await qa("entity SULFUR_CUBE")).valid);
      await qa("flag break true");
      await bot.send("interact", { entity: cube.id });
      await delay(400);
      assert.equal((await qa("entity SULFUR_CUBE")).valid, false);
    },
  );
  await scenario(
    "sulfur cube attack obeys animal killing permissions",
    async () => {
      const cube = await qa("spawn SULFUR_CUBE");
      await qa("held AIR");
      await qa("flag animal_killing false");
      const before = await qa("entity SULFUR_CUBE");
      await bot.send("attack", { entity: cube.id });
      await delay(300);
      assert.equal((await qa("entity SULFUR_CUBE")).health, before.health);
      await qa("flag animal_killing true");
      await bot.send("attack", { entity: cube.id });
      await delay(300);
      assert((await qa("entity SULFUR_CUBE")).health < before.health);
    },
  );
  await scenario(
    "explosive sulfur cube preserves protected blocks and destroys allowed blocks",
    async () => {
      for (const allowed of [false, true]) {
        await qa(`flag tnt_explode ${allowed}`);
        await qa("spawn SULFUR_CUBE TNT");
        await qa("explosion-target");
        await qa("ignite");
        await until(
          "cube actually exploded",
          async () => !(await qa("entity SULFUR_CUBE")).valid,
          { timeout: 5000, interval: 100 },
        );
        const block = await qa("explosion-snapshot");
        assert.equal(block, allowed ? "AIR" : "DIRT");
      }
    },
  );
  await scenario(
    "copper golem cannot take protected items across claim boundary",
    async () => {
      await qa("floor");
      await qa("flag hopper_outside false");
      await qa("golem");
      await delay(8000);
      assert.equal(await qa("golem-snapshot"), 7);
      await qa("flag hopper_outside true");
      await until(
        "golem consumes chest contents",
        async () => (await qa("golem-snapshot")) < 7,
        { timeout: 20000, interval: 300 },
      );
    },
  );
} catch (error) {
  failure = error.stack ?? String(error);
  console.error(failure);
} finally {
  for (const c of clients) {
    if (!c.closed) {
      c.child.stdin.write(JSON.stringify({ id: ++token, op: "quit" }) + "\n");
      c.child.stdin.end();
    }
  }
  for (const c of clients)
    await until("native client shutdown", () => c.closed, {
      timeout: 10000,
    }).catch(() => {
      c.child.kill("SIGTERM");
      failure ??= "Native client forced shutdown";
    });
  const shutdown = await server.stop();
  const issues = logIssues(await readFile(join(dir, "console.log"), "utf8"));
  await writeFile(
    join(dir, "native-trace.json"),
    JSON.stringify(trace, null, 2),
  );
  await writeFile(
    join(dir, "results.json"),
    JSON.stringify({ results, failure, shutdown, issues }, null, 2),
  );
  console.log(`RESULTS=${join(dir, "results.json")}`);
  if (failure || shutdown.forced || shutdown.code !== 0 || issues.length)
    process.exitCode = 1;
}
