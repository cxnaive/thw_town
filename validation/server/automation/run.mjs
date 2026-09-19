import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, cp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import mineflayer from "mineflayer";
import YAML from "yaml";
import { customPackets, version } from "./protocol.mjs";
import { artifacts, cache, readVerified } from "./download.mjs";
import { managedProcess, until, bounded, assertPortFree, logIssues } from "./harness.mjs";

const { values: options } = parseArgs({ options: {
  "accept-eula": { type: "boolean" },
  server: { type: "string", default: "folia" },
  port: { type: "string", default: "25596" },
  cache: { type: "string", default: cache },
  libraries: { type: "string" },
  jar: { type: "string" },
} });
if (!options["accept-eula"]) throw new Error("Read https://aka.ms/MinecraftEULA and pass --accept-eula.");
if (!["folia", "leaf"].includes(options.server)) throw new Error("Use folia or leaf");
const port = Number(options.port);
assert(Number.isInteger(port) && port >= 1024 && port <= 65535);
await assertPortFree(port);
const root = fileURLToPath(new URL("../../../", import.meta.url));
const pluginVersion = /^version = "([^"$]+)-\$suffixes"/m.exec(await readFile(join(root, "build.gradle.kts"), "utf8"))?.[1];
assert(pluginVersion, "Build version not found");
const jar = options.jar ?? join(root, "build/libs", `Dominion-${pluginVersion}-release-full.jar`);
const dir = await mkdtemp(join(tmpdir(), `dominion-guide-${options.server}-`));
await mkdir(join(dir, "plugins/Dominion"), { recursive: true });
await mkdir(join(dir, "cache"));
await mkdir(join(dir, "config"));
if (options.libraries) await cp(options.libraries, join(dir, "libraries"), { recursive: true });
await writeFile(join(dir, "server.jar"), await readVerified(options.cache, artifacts[options.server]));
await writeFile(join(dir, "cache", artifacts.mojang.filename), await readVerified(options.cache, artifacts.mojang));
await copyFile(jar, join(dir, "plugins/Dominion.jar"));
// Deliberately omit language: this verifies the production default. Database defaults to isolated SQLite.
await writeFile(join(dir, "plugins/Dominion/config.yml"), YAML.stringify({
  version: 3,
  "default-ui-type": "BY_PLAYER",
  "select-tool": "STICK",
  town: { "founding-cost": 0, "outpost-max-horizontal-size": 1234,
    "outpost-pricing": { "price-per-block500to1000": 0.25 },
    "level-requirements": { 2: { type: "item", material: "DIAMOND", amount: 17 } },
  },
}));
await writeFile(join(dir, "eula.txt"), "eula=true\n");
await writeFile(join(dir, "config/paper-global.yml"), "threaded-regions:\n  threads: 2\n");
await writeFile(join(dir, "server.properties"),
  `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nlevel-type=minecraft:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}]}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=4\ndifficulty=peaceful\nallow-flight=true\n`);
await writeFile(join(dir, "environment.json"), JSON.stringify({ server: options.server, version, pluginVersion,
  serverHash: artifacts[options.server].hash, pluginHash: createHash("sha256").update(await readFile(jar)).digest("hex"),
  port, node: process.version, database: "isolated SQLite", language: "omitted to test default" }, null, 2));
console.log(`RUN_DIR=${dir}`);
const server = managedProcess(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin/java") : "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
  ["-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4", "-jar", "server.jar", "--nogui"],
  { cwd: dir, log: join(dir, "console.log") });
const results = [];
const snapshots = [];
let bot, failure;
const messages = [];
async function scenario(name, run) {
  const start = performance.now();
  await run();
  results.push({ name, status: "PASS", elapsedMs: Math.round(performance.now() - start) });
  console.log(`PASS ${name}`);
}
function title() { return JSON.stringify(bot.currentWindow?.title ?? ""); }
function itemText(item) { return JSON.stringify(item?.components ?? item?.nbt ?? ""); }
function slots() { return bot.currentWindow?.slots.slice(0, bot.currentWindow.inventoryStart) ?? []; }
function inventoryText() { return slots().map(itemText).join("\n"); }
function templateFlags() {
  return slots().filter(item => itemText(item).includes("模板值：")).map(itemText).sort();
}
async function waitWindow(expected) {
  await until(`window ${expected}`, () => { server.check(); return title().includes(expected) && slots().some(Boolean); });
  await delay(150);
  snapshots.push({ title: bot.currentWindow.title, items: slots().map((item, slot) => ({ slot, name: item?.name, components: item?.components })) });
}
async function close() {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await until("closed", () => !bot.currentWindow);
}
async function commandWindow(command, expected) {
  await close();
  bot.chat(command);
  await waitWindow(expected);
}
async function click(text, expected) {
  const index = slots().findIndex(item => itemText(item).includes(text));
  assert(index >= 0, `Button ${text} not found in ${title()}`);
  await bounded(`click ${text}`, () => bot.clickWindow(index, 0, 0));
  if (expected) await waitWindow(expected);
}
async function chatCommand(command, text) {
  const start = messages.length;
  bot.chat(command);
  await until(`chat ${text}`, () => messages.slice(start).some(msg => msg.text.includes(text)));
  await delay(250);
  return messages.slice(start);
}
function clickEvents(json, label) {
  if (!json || typeof json !== "object") return [];
  const result = [];
  const event = json.clickEvent ?? json.click_event;
  if (event && JSON.stringify(json).includes(label)) result.push(event.value ?? event.command);
  for (const value of Object.values(json)) {
    if (typeof value === "object") {
      for (const entry of Array.isArray(value) ? value : [value]) result.push(...clickEvents(entry, label));
    }
  }
  return result.filter(Boolean);
}
try {
  await until("server startup", () => { server.check(); return server.state.tail.includes("Done ("); }, { timeout: 180000 });
  const config = YAML.parse(await readFile(join(dir, "plugins/Dominion/config.yml"), "utf8"));
  assert.equal(config.language, "zh_cn");
  bot = mineflayer.createBot({ host: "127.0.0.1", port, username: "TownGuideQA", version, customPackets,
    auth: "offline", disableChatSigning: true, hideErrors: true, logErrors: false });
  let botError;
  bot.on("error", error => { botError = error; });
  bot.on("kicked", reason => { botError = new Error(JSON.stringify(reason)); });
  bot.on("message", message => messages.push({ text: message.toString(), json: message.json }));
  bot.on("physicsTick", () => bot._client.write("tick_end", {}));
  await until("bot spawn", () => { server.check(); if (botError) throw botError; return bot.entity; }, { timeout: 60000 });
  await delay(1500);
  await scenario("townless player reads the Chinese handbook", async () => {
    // Follow the actual preference; if TUI is selected, use its existing switch button.
    bot.chat("/dom menu");
    await delay(750);
    if (!bot.currentWindow) {
      const commands = messages.flatMap(msg => clickEvents(msg.json, "切换到CUI"));
      assert(commands.length, "TUI switch button");
      bot.chat(commands.at(-1).replace(/^\/?/, "/"));
      await waitWindow("Dominion");
    }
    await commandWindow("/town help", "小镇手册");
    assert(inventoryText().includes("未加入小镇"));
    assert.equal(slots().slice(10, 17).filter(Boolean).length, 7);
  });
  const chapters = [
    ["start", "加入与创建", "STICK"], ["roles", "身份与权限组", "副镇长无权操作"],
    ["templates", "小镇模板与属地覆盖", "本地禁止"], ["trust", "访客与信任", "关闭不等于禁止"],
    ["land", "属地管理与计费", "1234"], ["levels", "等级与共同任务", "DIAMOND"],
    ["faq", "常见问题", "跨服"],
  ];
  for (const [topic, heading, expected] of chapters) await scenario(`read ${topic} and return to contents`, async () => {
    await click(heading, heading);
    assert(inventoryText().includes(expected), `Content ${expected}`);
    if (topic === "land") assert(inventoryText().includes("0.25"), "Configured area price");
    assert(!/\{(?:tool|price_\d|tasks|max_xz)\}/.test(inventoryText()), "Unexpanded config tokens");
    await click("手册目录", "小镇手册");
  });
  await scenario("invalid topic and oversized page remain usable", async () => {
    await commandWindow("/town guide invalid 2147483647", "小镇手册");
    await commandWindow("/town help land 2147483647", "属地管理与计费");
    assert(slots().some(item => item?.name === "paper"));
  });
  await scenario("main-menu handbook and back navigation", async () => {
    await commandWindow("/dom menu", "Dominion");
    await click("小镇手册", "小镇手册");
    await click("返回原页面", "Dominion");
  });
  await scenario("town template contextual help returns to the same settings", async () => {
    server.command("op TownGuideQA");
    await delay(400);
    await close();
    await chatCommand("/town create 手册测试镇", "手册测试镇");
    await commandWindow("/town", "小镇：");
    await click("小镇手册", "小镇手册");
    assert(inventoryText().includes("手册测试镇"));
    await click("小镇模板与属地覆盖", "小镇模板与属地覆盖");
    await click("打开小镇权限组", "小镇组");
    await click("小镇成员权限模板", "小镇权限模板");
    const originalFlags = templateFlags();
    assert(originalFlags.length > 0, "Visible template permissions");
    await click("本页帮助", "小镇模板与属地覆盖");
    await click("返回原页面", "小镇权限模板");
    assert.deepEqual(templateFlags(), originalFlags, "Reading help does not toggle any template flags");
  });
  await scenario("town trust contextual help preserves its return location", async () => {
    await commandWindow("/town help trust", "访客与信任");
    await click("打开小镇信任", "信任");
    await click("本页帮助", "访客与信任");
    await click("返回原页面", "信任");
    assert(!title().includes("访客与信任"), "Returned to the management page");
  });
  await scenario("TUI chapter links, pagination and back navigation", async () => {
    await commandWindow("/dom menu", "Dominion");
    await click("切换到TUI");
    await until("TUI switched", () => !bot.currentWindow);
    const index = await chatCommand("/town help", "小镇手册");
    const commands = index.flatMap(msg => clickEvents(msg.json, "小镇模板与属地覆盖"));
    assert(commands.length, "Clickable chapter link");
    const chapter = await chatCommand(commands.at(-1).replace(/^\/?/, "/"), "小镇模板与属地覆盖");
    const next = chapter.flatMap(msg => clickEvents(msg.json, "▶"));
    assert(next.length, "Clickable TUI next page");
    const secondPage = await chatCommand(next.at(-1).replace(/^\/?/, "/"), "小镇模板与属地覆盖");
    assert(secondPage.some(msg => msg.text.includes("[2/")), "Next-page button reaches page 2");
    const contents = secondPage.flatMap(msg => clickEvents(msg.json, "手册目录"));
    assert(contents.length, "Clickable contents navigation");
    await chatCommand(contents.at(-1).replace(/^\/?/, "/"), "小镇手册");
    await chatCommand("/town help trust 2", "访客与信任");
    await chatCommand("/town help land 2147483647", "属地管理与计费");
    assert(!bot.currentWindow, "TUI does not open an inventory");
  });
} catch (error) {
  failure = error.stack ?? String(error);
  console.error(failure);
} finally {
  bot?.quit();
  const shutdown = await server.stop();
  const logs = await readFile(join(dir, "console.log"), "utf8");
  const issues = logIssues(logs);
  // The two offline-mode auth calls may fail in a network-isolated environment; record separately.
  const report = { results, failure, shutdown, issues };
  await writeFile(join(dir, "results.json"), JSON.stringify(report, null, 2));
  await writeFile(join(dir, "ui-snapshots.json"), JSON.stringify(snapshots, null, 2));
  await writeFile(join(dir, "chat.json"), JSON.stringify(messages, null, 2));
  console.log(`RESULTS=${join(dir, "results.json")}`);
  if (failure || shutdown.forced || shutdown.code !== 0 || issues.length) process.exitCode = 1;
}
