import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, cp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import { DatabaseSync } from "node:sqlite";
import mineflayer from "mineflayer";
import YAML from "yaml";
import { customPackets, version } from "./protocol.mjs";
import { artifacts, cache, readVerified } from "./download.mjs";
import { managedProcess, until, bounded, assertPortFree, logIssues } from "./harness.mjs";

const { values: options } = parseArgs({ options: {
  "accept-eula": { type: "boolean" }, server: { type: "string", default: "folia" },
  port: { type: "string", default: "25596" }, cache: { type: "string", default: cache },
  libraries: { type: "string" }, jar: { type: "string" }, "server-jar": { type: "string" },
} });
if (!options["accept-eula"]) throw new Error("Read https://aka.ms/MinecraftEULA and pass --accept-eula.");
const port = Number(options.port);
assert(Number.isInteger(port) && port >= 1024 && port <= 65535);
await assertPortFree(port);
const root = fileURLToPath(new URL("../../../", import.meta.url));
const pluginVersion = /^version = "([^"$]+)-\$suffixes"/m.exec(await readFile(join(root, "build.gradle.kts"), "utf8"))?.[1];
assert(pluginVersion);
const pluginJar = options.jar ?? join(root, "build/libs", `Dominion-${pluginVersion}-release-full.jar`);
const serverJar = options["server-jar"] ? await readFile(options["server-jar"]) : await readVerified(options.cache, artifacts[options.server]);
const dir = await mkdtemp(join(tmpdir(), `dominion-teleport-${options.server}-`));
await mkdir(join(dir, "plugins/Dominion"), { recursive: true });
await mkdir(join(dir, "cache"));
await mkdir(join(dir, "config"));
if (options.libraries) await cp(options.libraries, join(dir, "libraries"), { recursive: true });
await writeFile(join(dir, "server.jar"), serverJar);
await writeFile(join(dir, "cache", artifacts.mojang.filename), await readVerified(options.cache, artifacts.mojang));
await copyFile(pluginJar, join(dir, "plugins/Dominion.jar"));
await writeFile(join(dir, "plugins/Dominion/config.yml"), YAML.stringify({ version: 3, "default-ui-type": "BY_PLAYER" }));
await writeFile(join(dir, "eula.txt"), "eula=true\n");
await writeFile(join(dir, "config/paper-global.yml"), "threaded-regions:\n  threads: 2\n");
await writeFile(join(dir, "server.properties"),
  `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nlevel-type=minecraft:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}]}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=4\ndifficulty=peaceful\nallow-flight=true\n`);
await writeFile(join(dir, "environment.json"), JSON.stringify({ server: options.server, version, pluginVersion,
  serverHash: createHash("sha256").update(serverJar).digest("hex"),
  pluginHash: createHash("sha256").update(await readFile(pluginJar)).digest("hex"), port, node: process.version,
  database: "isolated SQLite" }, null, 2));
console.log(`RUN_DIR=${dir}`);
const server = managedProcess(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin/java") : "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
  ["-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4", "-jar", "server.jar", "--nogui"],
  { cwd: dir, log: join(dir, "console.log") });
let bot, db, failure, abilities;
const messages = [], results = [], snapshots = [];
const position = () => ({ dimension: bot.game.dimension, ...bot.entity.position, yaw: bot.entity.yaw, pitch: bot.entity.pitch });
async function scenario(name, action) {
  const begin = messages.length;
  await action();
  const result = { name, status: "PASS", position: position(), messages: messages.slice(begin).map(message => message.text) };
  results.push(result);
  console.log(`PASS ${name}`);
}
async function moveTo(dimension, x = 1000.5, y = 150, z = 1000.5) {
  server.command(`execute in minecraft:${dimension} run tp TpQA ${x} ${y} ${z}`);
  await until("vanilla teleport", () => String(bot.game.dimension).includes(dimension) && Math.abs(bot.entity.position.x - x) < .1);
  await delay(300);
}
async function chat(command, expected) {
  const begin = messages.length;
  bot.chat(command);
  await until(`message ${expected}`, () => { server.check(); return messages.slice(begin).some(message => message.text.includes(expected)); });
}
function tp(id) { return "/dom tp " + db.prepare("SELECT name FROM dominion WHERE id=?").get(id).name; }
function clicks(json, label) {
  if (!json || typeof json !== "object") return [];
  const event = json.clickEvent ?? json.click_event;
  const result = event && JSON.stringify(json).includes(label) ? [event.value ?? event.command] : [];
  for (const value of Object.values(json)) if (typeof value === "object") {
    for (const entry of Array.isArray(value) ? value : [value]) result.push(...clicks(entry, label));
  }
  return result.filter(Boolean);
}
async function teleport(command, dimension, custom = true) {
  await chat(command, "传送完成");
  assert(String(bot.game.dimension).includes(dimension));
  assert(Math.abs(bot.entity.position.x - 100) < 2);
  assert.equal(bot.entity.position.y, 80, "Feet must be on the platform, not inside it");
  if (custom) {
    assert(Math.abs(bot.entity.yaw - Math.PI / 2) < .01, "Custom yaw must survive safety resolution");
    assert(Math.abs(bot.entity.pitch + 25 * Math.PI / 180) < .01, "Custom pitch must survive safety resolution");
  }
}
const itemText = item => JSON.stringify(item?.components ?? item?.nbt ?? "");
async function window(command, title) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await delay(200); }
  bot.chat(command);
  await until(`window ${title}`, () => (JSON.stringify(bot.currentWindow?.title) ?? "").includes(title));
  await delay(200);
}
try {
  await until("server startup", () => { server.check(); return server.state.tail.includes("Done ("); }, { timeout: 180000 });
  const config = YAML.parse(await readFile(join(dir, "plugins/Dominion/config.yml"), "utf8"));
  assert.equal(config.language, "zh_cn");
  assert.equal(config["safe-teleport"]["search-radius"], 8);
  bot = mineflayer.createBot({ host: "127.0.0.1", port, username: "TpQA", version, customPackets,
    auth: "offline", disableChatSigning: true, hideErrors: true, logErrors: false });
  let botError;
  bot.on("error", error => { botError = error; });
  bot.on("kicked", reason => { botError = new Error(JSON.stringify(reason)); });
  bot.on("message", message => messages.push({ text: message.toString(), json: message.json }));
  bot._client.on("abilities", packet => { abilities = packet; });
  bot.on("physicsTick", () => bot._client.write("tick_end", {}));
  await until("bot spawn", () => { server.check(); if (botError) throw botError; return bot.entity; }, { timeout: 60000 });
  await delay(1200);
  server.command("op TpQA");
  server.command("gamemode creative TpQA");
  await delay(400);
  bot.physicsEnabled = false;
  bot.chat("/dom switch_ui CUI");
  await delay(400);
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  for (const dimension of ["the_nether", "the_end", "overworld"]) {
    await moveTo(dimension, 100.5, 80, 100.5);
    if (dimension === "the_nether") {
      // Isolate the fixture from randomly generated lava pockets exposed by clearing terrain.
      server.command("execute in minecraft:the_nether run fill 96 79 96 104 111 104 stone");
    }
    server.command(`execute in minecraft:${dimension} run fill 97 79 97 103 79 103 stone`);
    server.command(`execute in minecraft:${dimension} run fill 97 80 97 103 110 103 air`);
    await delay(300);
  }
  await chat("/town create TpAudit", "TpAudit");
  db = new DatabaseSync(join(dir, "plugins/Dominion/dominion.db"));
  await until("town row", () => db.prepare("SELECT * FROM dominion WHERE name='TpAudit'").get());
  const template = db.prepare("SELECT * FROM dominion WHERE name='TpAudit'").get();
  const worlds = db.prepare("SELECT * FROM world_map").all();
  snapshots.push({ template, worlds });
  await scenario("teleport-point event can read old default coordinates from another dimension", async () => {
    await moveTo("the_end");
    // The event reads getTpLocation() before validating this deliberately invalid new location.
    // A coordinate lookup must not attempt terrain access in the dominion's overworld region.
    await chat("/dom set_tp TpAudit", "设置的传送点不在领地");
    assert.equal(db.prepare("SELECT tp_location FROM dominion WHERE id=?").get(template.id).tp_location, "default");
  });
  const claims = { overworld: template.id };
  function insert(values) {
    const row = { ...template, ...values };
    delete row.id;
    const keys = Object.keys(row);
    return Number(db.prepare(`INSERT INTO dominion (${keys.map(key => '"' + key + '"').join(",")}) VALUES (${keys.map(() => "?").join(",")})`)
      .run(...keys.map(key => row[key])).lastInsertRowid);
  }
  for (const dimension of ["the_nether", "the_end"]) {
    claims[dimension] = insert({ name: dimension, world_uid: worlds.find(world => world.name === (dimension === "the_end" ? "world_the_end" : "world_nether")).uid,
      tp_location: "100:80:100:90:25", claim_kind: "NORMAL" });
  }
  for (const [source, target, mode] of [
    ["overworld", "overworld", "custom"], ["the_nether", "overworld", "custom"],
    ["the_end", "overworld", "custom"], ["the_nether", "overworld", "default"],
    ["overworld", "the_nether", "custom"], ["overworld", "the_nether", "default"],
    ["overworld", "the_end", "custom"], ["overworld", "the_end", "default"],
  ]) {
    await scenario(`${source} -> ${target} (${mode})`, async () => {
      db.prepare("UPDATE dominion SET tp_location=? WHERE id=?").run(mode === "custom" ? "100:80:100:90:25" : "default", claims[target]);
      await moveTo(source);
      await teleport(tp(claims[target]), target, mode === "custom");
    });
  }
  await scenario("town GUI right-click crosses dimension and closes GUI", async () => {
    await moveTo("overworld");
    await window("/town help land", "属地");
    const open = bot.currentWindow.slots.findIndex(item => itemText(item).includes("打开属地列表"));
    assert(open >= 0);
    await bounded("open outposts", () => bot.clickWindow(open, 0, 0));
    await until("outpost items", () => bot.currentWindow?.slots.some(item => itemText(item).includes("the_end")));
    const slots = bot.currentWindow.slots.slice(0, bot.currentWindow.inventoryStart);
    const index = slots.findIndex(item => itemText(item).includes("the_end"));
    assert(index >= 0, "End outpost button");
    const begin = messages.length;
    await bounded("right click", () => bot.clickWindow(index, 1, 0));
    await until("GUI teleport completed", () => messages.slice(begin).some(message => message.text.includes("传送完成")));
    assert(String(bot.game.dimension).includes("the_end"));
    assert.equal(bot.currentWindow, null);
  });
  await scenario("legacy GUI right-click crosses dimension", async () => {
    await moveTo("overworld");
    await window("/dom list", "领地列表");
    const index = bot.currentWindow.slots.findIndex(item => itemText(item).includes("the_end"));
    assert(index >= 0);
    const begin = messages.length;
    await bounded("legacy right click", () => bot.clickWindow(index, 1, 0));
    await until("legacy GUI teleport", () => messages.slice(begin).some(message => message.text.includes("传送完成")));
    assert(String(bot.game.dimension).includes("the_end"));
    assert.equal(bot.currentWindow, null);
  });
  await scenario("town TUI teleport button crosses dimension", async () => {
    await moveTo("overworld");
    bot.chat("/dom switch_ui TUI");
    await delay(300);
    let begin = messages.length;
    await chat("/town help land", "打开属地列表");
    const open = messages.slice(begin).flatMap(message => clicks(message.json, "打开属地列表")).at(-1);
    assert(open);
    begin = messages.length;
    await chat(open.replace(/^\/?/, "/"), "the_nether");
    const action = messages.slice(begin).filter(message => message.text.includes("the_nether"))
      .flatMap(message => clicks(message.json, "传送")).at(-1);
    assert(action, "TUI teleport command");
    await teleport(action.replace(/^\/?/, "/"), "the_nether", false);
  });
  await scenario("legacy TUI teleport button crosses dimension", async () => {
    await moveTo("overworld");
    const begin = messages.length;
    await chat("/dom list", "the_end");
    const action = messages.slice(begin).filter(message => message.text.includes("the_end"))
      .flatMap(message => clicks(message.json, "TP")).at(-1);
    assert(action);
    await teleport(action.replace(/^\/?/, "/"), "the_end", false);
  });
  await scenario("missing world reports failure without charging cooldown", async () => {
    const missing = insert({ name: "MissingWorld", world_uid: "11111111-1111-1111-1111-111111111111" });
    await moveTo("the_end");
    server.command("deop TpQA");
    await delay(300);
    const before = position();
    await chat(tp(missing), "目标世界未加载");
    assert.deepEqual(position(), before);
    await teleport(tp(claims.overworld), "overworld", false);
  });
  await scenario("successful arrival charges normal-player cooldown", async () => {
    await chat(tp(claims.the_end), "请等待");
    assert(String(bot.game.dimension).includes("overworld"));
  });
  await delay(10500);
  await scenario("duplicate click is coalesced and movement cancels without cooldown", async () => {
    await moveTo("overworld");
    await chat(tp(claims.the_end), "秒后传送");
    await chat(tp(claims.the_end), "正在处理");
    const begin = messages.length;
    await moveTo("overworld", 1002.5);
    await until("cancel feedback", () => messages.slice(begin).some(message => message.text.includes("取消")));
    await teleport(tp(claims.the_nether), "the_nether", false);
    await delay(1200);
    assert(String(bot.game.dimension).includes("the_nether"), "Old callback must not teleport later");
  });
  await scenario("empty high claim uses checked air fallback without granting flight", async () => {
    server.command("op TpQA");
    server.command("gamemode survival TpQA");
    await delay(300);
    const empty = insert({ name: "EmptyHighClaim", x1: 10000, x2: 10032, z1: 10000, z2: 10032, y1: 200, y2: 230, tp_location: "default" });
    await moveTo("overworld");
    await chat(tp(empty), "空中落点");
    assert(bot.entity.position.x >= 10000 && bot.entity.position.x < 10032);
    assert(bot.entity.position.z >= 10000 && bot.entity.position.z < 10032);
    assert.equal(bot.entity.position.y, 215);
    assert(abilities, "Player abilities packet");
    assert.equal(abilities.flags & 6, 0, "No flying or flight permission added");
    server.command("gamemode creative TpQA");
    await delay(300);
  });
  // Deliberately separate underground floor and outdoor surface. No cache refresh assumptions.
  await moveTo("overworld", 408.5, 85, 408.5);
  server.command("execute in minecraft:overworld run fill 398 58 398 418 100 418 air");
  server.command("execute in minecraft:overworld run fill 398 59 398 418 59 418 stone");
  server.command("execute in minecraft:overworld run fill 398 79 398 418 79 418 stone");
  await delay(500);
  const cave = insert({ name: "CaveAudit", x1: 400, x2: 417, z1: 400, z2: 417, y1: 50, y2: 100, tp_location: "default" });
  await scenario("automatic search takes outdoor floor and excludes the cave underneath", async () => {
    await moveTo("the_end");
    await chat(tp(cave), "传送完成");
    assert.equal(bot.entity.position.y, 80);
    assert(String(bot.game.dimension).includes("overworld"));
  });
  await scenario("explicit underground point is respected instead of the roof", async () => {
    db.prepare("UPDATE dominion SET tp_location=? WHERE id=?").run("408:60:408:90:25", cave);
    await moveTo("the_end");
    await chat(tp(cave), "传送完成");
    assert.equal(bot.entity.position.y, 60);
    assert.equal(bot.entity.position.x, 408);
    assert(Math.abs(bot.entity.yaw - Math.PI / 2) < .01);
  });
  await scenario("blocked explicit point adjusts nearby without jumping to roof or deep cave", async () => {
    server.command("execute in minecraft:overworld run setblock 408 60 408 stone");
    await delay(200);
    await moveTo("the_end");
    await chat(tp(cave), "传送完成");
    assert(Math.abs(bot.entity.position.y - 60) <= 4, "Blocked point should only adjust within the local height range");
    assert(Math.abs(bot.entity.position.x - 408) <= 2);
  });
  await scenario("underground-only auto target cannot bypass sky filter through air fallback", async () => {
    const hidden = insert({ name: "HiddenCave", x1: 400, x2: 417, z1: 400, z2: 417, y1: 50, y2: 75, tp_location: "default" });
    await moveTo("the_end");
    const before = position();
    await chat(tp(hidden), "未找到可用落点");
    assert.deepEqual(position(), before);
  });
  await scenario("default landing follows the actual half-slab surface", async () => {
    await moveTo("overworld", 504.5, 85, 504.5);
    server.command("execute in minecraft:overworld run fill 494 79 494 514 110 514 air");
    server.command("execute in minecraft:overworld run fill 494 79 494 514 79 514 stone_slab[type=bottom]");
    await delay(300);
    const slab = insert({ name: "SlabAudit", x1: 496, x2: 513, z1: 496, z2: 513, y1: 70, y2: 100, tp_location: "default" });
    await moveTo("the_end");
    await chat(tp(slab), "传送完成");
    assert.equal(bot.entity.position.y, 79.5);
  });
  await scenario("passable grass does not hide the outdoor ground", async () => {
    server.command("execute in minecraft:overworld run fill 494 79 494 514 79 514 grass_block");
    server.command("execute in minecraft:overworld run fill 494 80 494 514 80 514 short_grass");
    await delay(300);
    await moveTo("the_end");
    await chat("/dom tp SlabAudit", "传送完成");
    assert.equal(bot.entity.position.y, 80);
    await until("grass remains at the landing position", () => bot.blockAt(bot.entity.position.floored())?.name === "short_grass");
  });
} catch (error) {
  failure = error.stack ?? String(error);
  console.error(failure);
} finally {
  db?.close();
  bot?.quit();
  const shutdown = await server.stop();
  const issues = logIssues(await readFile(join(dir, "console.log"), "utf8"));
  await writeFile(join(dir, "results.json"), JSON.stringify({ results, failure, shutdown, issues }, null, 2));
  await writeFile(join(dir, "fixtures.json"), JSON.stringify(snapshots, null, 2));
  await writeFile(join(dir, "chat.json"), JSON.stringify(messages, null, 2));
  console.log(`RESULTS=${join(dir, "results.json")}`);
  if (failure || shutdown.forced || shutdown.code !== 0 || issues.length) process.exitCode = 1;
}
