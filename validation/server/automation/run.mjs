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
import { runSearchScenarios } from "./search-scenarios.mjs";

const { values: options } = parseArgs({ options: {
  "accept-eula": { type: "boolean" },
  server: { type: "string", default: "folia" },
  port: { type: "string", default: "25596" },
  cache: { type: "string", default: cache },
  libraries: { type: "string" },
  jar: { type: "string" },
  search: { type: "boolean", default: false },
} });
if (!options["accept-eula"]) throw new Error("Read https://aka.ms/MinecraftEULA and pass --accept-eula.");
if (!["folia", "leaf", "folia26_2"].includes(options.server)) throw new Error("Use folia, leaf or folia26_2");
const serverArtifact = artifacts[options.server];
assert(serverArtifact, "Unknown server profile");
const mojangArtifact = artifacts[serverArtifact.mojang ?? "mojang"];
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
await writeFile(join(dir, "cache", mojangArtifact.filename), await readVerified(options.cache, mojangArtifact));
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
for (const name of serverArtifact.plugins ?? []) {
  const artifact = artifacts[name];
  await writeFile(join(dir, "plugins", artifact.filename), await readVerified(options.cache, artifact));
}
await writeFile(join(dir, "config/paper-global.yml"), "threaded-regions:\n  threads: 2\n");
if (options.search) await writeFile(join(dir, "spigot.yml"), YAML.stringify({ commands: {
  // Only this isolated test server: ordinary-player searches issue commands faster than a human.
  "enable-spam-exclusions": true, "spam-exclusions": ["/dom", "/town"],
} }));
await writeFile(join(dir, "server.properties"),
  `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nlevel-type=minecraft:flat\ngenerator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":3},{"block":"minecraft:grass_block","height":1}]}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=4\ndifficulty=peaceful\nallow-flight=true\n`);
await writeFile(join(dir, "environment.json"), JSON.stringify({ server: options.server, serverVersion: serverArtifact.version, clientVersion: version,
  protocolTranslation: serverArtifact.plugins ?? [], version, pluginVersion,
  serverHash: artifacts[options.server].hash, pluginHash: createHash("sha256").update(await readFile(jar)).digest("hex"),
  port, node: process.version, database: "isolated SQLite", language: "omitted to test default" }, null, 2));
console.log(`RUN_DIR=${dir}`);
const server = managedProcess(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin/java") : (options.server === "folia26_2" ? "/usr/lib/jvm/java-25-openjdk-amd64/bin/java" : "/usr/lib/jvm/java-21-openjdk-amd64/bin/java"),
  ["-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4", "-jar", "server.jar", "--nogui"],
  { cwd: dir, log: join(dir, "console.log") });
const results = [];
const snapshots = [];
let bot, db, failure;
const originFixtures = [
  { name: "自建样例", contributor: false, otherOwner: false },
  { name: "贡献样例", contributor: true, otherOwner: false },
  { name: "代管自建", contributor: false, otherOwner: true },
  { name: "代管贡献", contributor: true, otherOwner: true },
];
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
  return slots().filter(item => itemText(item).includes("全镇默认值：")).map(itemText).sort();
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
  // Player cache writes may coalesce for four seconds during login/startup.
  await delay(5000);
  await scenario("townless player reads the Chinese handbook", async () => {
    // Follow the actual preference; if TUI is selected, use its existing switch button.
    bot.chat("/dom menu");
    await delay(750);
    if (!bot.currentWindow) {
      const commands = messages.flatMap(msg => clickEvents(msg.json, "切换箱子菜单"));
      assert(commands.length, "TUI switch button");
      bot.chat(commands.at(-1).replace(/^\/?/, "/"));
      await waitWindow("Dominion");
    }
    await commandWindow("/town help", "小镇手册");
    assert(inventoryText().includes("未加入小镇"));
    assert.equal(slots().slice(10, 17).filter(Boolean).length, 7);
  });
  const chapters = [
    ["start", "开始玩小镇", "小镇和属地是什么", "/town join"],
    ["roles", "谁能做什么", "权限是什么", "builders"],
    ["templates", "权限怎么继承", "全镇默认权限是什么", "仓库仍然禁止"],
    ["trust", "让镇外朋友来帮忙", "什么时候用信任", "删除这个人"],
    ["land", "管理小镇属地", "怎样圈选新属地", "0.25"],
    ["levels", "一起升级小镇", "升级有什么用", "DIAMOND"],
    ["faq", "遇到问题怎么办", "为什么我不能放方块", "跨服"],
  ];
  for (const [topic, heading, firstHeading, expected] of chapters) await scenario(`read ${topic} lesson by lesson and return to contents`, async () => {
    await click(heading, heading);
    assert(inventoryText().includes(firstHeading), `Opening lesson ${firstHeading}`);
    assert(!inventoryText().includes(expected), "Later lesson is not dumped on the opening page");
    let allText = inventoryText();
    let page = 1;
    while (slots().some(item => item?.name === "arrow" && itemText(item).includes("下一节"))) {
      assert.equal(slots().filter(item => item?.name === "paper").length, 1, "One complete lesson per page");
      const previous = itemText(slots().find(item => item?.name === "paper"));
      await click("下一节", heading);
      await until("next lesson body", () => itemText(slots().find(item => item?.name === "paper")) !== previous);
      page++;
      assert(page <= 20, "No pagination loop");
      assert(inventoryText().includes(`第 ${page}/`), "Visible lesson progress");
      assert(!/\{(?:tool|price_\d|tasks|max_xz)\}/.test(inventoryText()), "Unexpanded config tokens");
      allText += inventoryText();
    }
    assert(allText.includes(expected), `Later content ${expected}`);
    if (topic === "land") {
      assert(allText.includes("1234"), "Configured size");
      assert(allText.includes("木棍 (STICK)"), "Localized selection tool");
    }
    if (topic === "templates") {
      await click("上一节", heading);
      assert(inventoryText().includes("到哪里设置"), "Previous lesson stays in this chapter");
      await click("下一节", heading);
      await click("下一章", "让镇外朋友来帮忙");
      assert(inventoryText().includes("什么时候用信任"), "Last lesson leads to the next chapter");
    }
    await click("手册目录", "小镇手册");
  });
  await scenario("invalid topic and oversized page remain usable", async () => {
    await commandWindow("/town guide invalid 2147483647", "小镇手册");
    await commandWindow("/town help land 2147483647", "管理小镇属地");
    assert(slots().some(item => item?.name === "paper"));
  });
  await scenario("main-menu handbook and back navigation", async () => {
    await commandWindow("/dom menu", "Dominion");
    assert(inventoryText().includes("手动套用时生效"), "Personal presets explain their one-time application");
    await click("个人权限预设（旧领地）", "个人权限预设（旧领地）");
    await click("返回主菜单", "Dominion");
    await click("小镇手册", "小镇手册");
    await click("返回原页面", "Dominion");
  });
  await scenario("town template contextual help returns to the same settings", async () => {
    server.command("op TownGuideQA");
    await delay(400);
    await close();
    await chatCommand("/town create 手册测试镇", "手册测试镇");
    await commandWindow("/town", "小镇：");
    assert(inventoryText().includes("成员权限管理"), "Town home names the member-permission entry");
    assert(inventoryText().includes("建造、破坏、开箱"), "Entry explains what players can manage");
    await click("成员权限管理", "成员权限组");
    await click("小镇成员（默认组）", "全镇默认权限");
    assert(title().includes("小镇成员（默认组）") && !title().includes("@town"));
    await commandWindow("/town", "小镇：");
    await click("小镇手册", "小镇手册");
    assert(inventoryText().includes("手册测试镇"));
    await click("权限怎么继承", "权限怎么继承");
    await click("打开成员权限管理", "成员权限组");
    await click("小镇成员（默认组）", "全镇默认权限");
    const originalFlags = templateFlags();
    assert(originalFlags.length > 0, "Visible template permissions");
    await click("本页帮助", "权限怎么继承");
    await click("返回原页面", "全镇默认权限");
    assert.deepEqual(templateFlags(), originalFlags, "Reading help does not toggle any template flags");
  });
  await scenario("custom permission groups show their display name in default settings", async () => {
    await close();
    await chatCommand("/town group create builders 建筑组", "创建成员权限组");
    await commandWindow("/town", "小镇：");
    await click("成员权限管理", "成员权限组");
    await click("建筑组", "成员权限组");
    await click("全镇默认权限", "全镇默认权限");
    assert(title().includes("建筑组") && !title().includes("builders"), "Show the group's player-facing name");
  });
  await scenario("town trust contextual help preserves its return location", async () => {
    await commandWindow("/town help trust", "让镇外朋友来帮忙");
    await click("打开镇外玩家权限（信任）", "信任");
    await click("本页帮助", "让镇外朋友来帮忙");
    await click("返回原页面", "信任");
    assert(!title().includes("让镇外朋友来帮忙"), "Returned to the management page");
  });
  await scenario("native and attached land have distinct origins in both chest lists", async () => {
    await close();
    db = new DatabaseSync(join(dir, "plugins/Dominion/dominion.db"));
    db.exec("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");
    const founding = db.prepare("SELECT * FROM dominion WHERE name='手册测试镇'").get();
    assert(founding && founding.attached_by === null && founding.claim_kind === "FOUNDING");
    const otherOwner = "10000000-0000-0000-0000-000000000001";
    function insert(table, row) {
      const copy = { ...row };
      delete copy.id;
      const keys = Object.keys(copy);
      db.prepare(`INSERT INTO ${table} (${keys.map(key => '"' + key + '"').join(",")}) VALUES (${keys.map(() => "?").join(",")})`)
        .run(...keys.map(key => copy[key]));
    }
    // All fixtures live in this run's temporary SQLite database. Rename through
    // the plugin afterwards so its normal cache publication path is exercised.
    const player = db.prepare("SELECT * FROM player_name WHERE uuid=?").get(founding.owner);
    insert("player_name", { ...player, uuid: otherOwner, last_known_name: "OtherCreatorQA" });
    for (const [i, fixture] of originFixtures.entries()) {
      insert("dominion", { ...founding, name: fixture.name + "临时", claim_kind: "NORMAL",
        owner: fixture.otherOwner ? otherOwner : founding.owner,
        attached_by: fixture.contributor ? founding.owner : null,
        x1: founding.x1 + (i + 1) * 1000, x2: founding.x2 + (i + 1) * 1000 });
      await chatCommand(`/dom rename ${fixture.name}临时 ${fixture.name}`, "重命名");
      assert(db.prepare("SELECT id FROM dominion WHERE name=?").get(fixture.name), "Fixture renamed and loaded");
    }
    for (const townList of [true, false]) {
      if (townList) {
        await commandWindow("/town help land", "管理小镇属地");
        await click("打开属地列表", "的小镇属地");
      } else await commandWindow("/dom list", "领地列表");
      const entries = [{ name: "手册测试镇", contributor: false }, ...originFixtures];
      for (const fixture of entries) {
        const row = slots().find(item => ["filled_map", "brown_banner", "beacon"].includes(item?.name)
          && itemText(item).includes(fixture.name));
        assert(row, `Visible ${fixture.name}`);
        const text = itemText(row);
        assert(text.includes(fixture.contributor ? "贡献者" : "创建者"), `Correct origin for ${fixture.name}`);
        assert(!text.includes(fixture.contributor ? "创建者" : "贡献者"), `No incorrect origin for ${fixture.name}`);
        if (townList) {
          assert(text.includes(fixture.name === "手册测试镇" ? "建镇属地" : "普通领地"));
          assert(!/NORMAL|FOUNDING/.test(text), "Land type is localized");
        }
        if (!townList && row.name === "brown_banner" && !fixture.contributor) {
          assert(text.includes("小镇自建属地") && !text.includes("已贡献"), "Native land has its own status");
        }
      }
    }
  });
  await scenario("trust lists and independent controls use localized categories and states", async () => {
    const controls = ["bricks", "diamond_pickaxe", "lever", "crafting_table"];
    const labels = ["建造", "破坏", "机关与容器", "物品使用"];
    for (const scope of ["town", "outpost"]) {
      await close();
      const target = scope === "town" ? "town" : "outpost 手册测试镇";
      const notice = await chatCommand(`/town trust TownGuideQA ${target} false false false false`, "上信任");
      assert(notice.some(msg => msg.text.includes(scope === "town" ? "范围 全镇" : "范围 仅此属地")));
      await commandWindow("/town help trust", "让镇外朋友来帮忙");
      await click("打开镇外玩家权限（信任）", "小镇信任");
      if (scope === "outpost") {
        const index = slots().findIndex(item => item?.name === "grass_block" && itemText(item).includes("手册测试镇"));
        assert(index >= 0);
        await bounded("open outpost trust", () => bot.clickWindow(index, 0, 0));
        await waitWindow("属地信任");
      }
      const head = slots().find(item => item?.name === "player_head" && itemText(item).includes("TownGuideQA"));
      assert(head && itemText(head).includes("额外允许：") && itemText(head).includes("无"));
      await click("TownGuideQA", "信任：TownGuideQA");
      const expected = [0, 0, 0, 0];
      for (const [i, material] of controls.entries()) {
        const index = slots().findIndex(item => item?.name === material);
        assert(index >= 0 && itemText(slots()[index]).includes(labels[i]));
        assert(itemText(slots()[index]).includes("不额外授权"));
        await bounded(`toggle ${scope} ${labels[i]}`, () => bot.clickWindow(index, 0, 0));
        await until("localized enabled trust", () => {
          const control = slots().find(item => item?.name === material);
          return control && itemText(control).includes("额外允许");
        });
        expected[i] = 1;
        const row = db.prepare('SELECT build,destroy,"switch",item_use FROM town_trust WHERE scope_type=?').get(scope.toUpperCase());
        assert.deepEqual([row.build, row.destroy, row.switch, row.item_use], expected, "Only the selected category changes");
      }
      await click("返回信任列表", scope === "town" ? "小镇信任" : "属地信任");
      const summary = itemText(slots().find(item => item?.name === "player_head" && itemText(item).includes("TownGuideQA")));
      for (const label of labels) assert(summary.includes(label), `Summary names ${label}`);
      assert(!summary.includes("BDSI"));
    }
  });
  await scenario("TUI chapter links, pagination and back navigation", async () => {
    await commandWindow("/dom menu", "Dominion");
    await click("切换聊天菜单");
    await until("TUI switched", () => !bot.currentWindow);
    const index = await chatCommand("/town help", "小镇手册");
    const commands = index.flatMap(msg => clickEvents(msg.json, "权限怎么继承"));
    assert(commands.length, "Clickable chapter link");
    const chapter = await chatCommand(commands.at(-1).replace(/^\/?/, "/"), "权限怎么继承");
    const next = chapter.flatMap(msg => clickEvents(msg.json, "下一节"));
    assert(next.length, "Clickable TUI next page");
    const secondPage = await chatCommand(next.at(-1).replace(/^\/?/, "/"), "权限怎么继承");
    assert(secondPage.some(msg => msg.text.includes("第 2/")), "Next-page button reaches page 2");
    const contents = secondPage.flatMap(msg => clickEvents(msg.json, "手册目录"));
    assert(contents.length, "Clickable contents navigation");
    await chatCommand(contents.at(-1).replace(/^\/?/, "/"), "小镇手册");
    await chatCommand("/town help trust 2", "让镇外朋友来帮忙");
    await chatCommand("/town help land 2147483647", "管理小镇属地");
    assert(!bot.currentWindow, "TUI does not open an inventory");
  });
  await scenario("chat lists also distinguish creators and contributors", async () => {
    const help = await chatCommand("/town help land", "管理小镇属地");
    const commands = help.flatMap(msg => clickEvents(msg.json, "打开属地列表"));
    assert(commands.length, "Outpost list link");
    const townRows = await chatCommand(commands.at(-1).replace(/^\/?/, "/"), "的小镇属地");
    const legacyRows = await chatCommand("/dom list", "你的领地列表");
    for (const rows of [townRows, legacyRows]) for (const fixture of originFixtures) {
      const row = rows.find(msg => msg.text.includes(fixture.name));
      assert(row, `Visible chat row ${fixture.name}`);
      assert(row.text.includes(fixture.contributor ? "贡献者" : "创建者"));
      assert(!row.text.includes(fixture.contributor ? "创建者" : "贡献者"));
    }
  });
  await scenario("TUI member permissions link opens the labeled default group", async () => {
    const help = await chatCommand("/town help templates", "权限怎么继承");
    const groups = help.flatMap(msg => clickEvents(msg.json, "打开成员权限管理"));
    assert(groups.length);
    const list = await chatCommand(groups.at(-1).replace(/^\/?/, "/"), "成员权限组");
    const defaultGroup = list.find(msg => msg.text.includes("小镇成员（默认组）"));
    assert(defaultGroup);
    const defaults = clickEvents(defaultGroup.json, "全镇默认权限");
    assert(defaults.length);
    const flags = await chatCommand(defaults.at(-1).replace(/^\/?/, "/"), "全镇默认权限");
    assert(flags.some(msg => msg.text.includes("全镇默认权限") && msg.text.includes("小镇成员（默认组）")));
    assert(!flags.some(msg => msg.text.includes("@town")));
  });
  await scenario("chat trust controls display access states and toggle the named category", async () => {
    const help = await chatCommand("/town help trust", "让镇外朋友来帮忙");
    const links = help.flatMap(msg => clickEvents(msg.json, "打开镇外玩家权限（信任）"));
    assert(links.length);
    const rows = await chatCommand(links.at(-1).replace(/^\/?/, "/"), "小镇信任");
    const row = rows.find(msg => msg.text.includes("TownGuideQA") && msg.text.includes("额外允许"));
    assert(row);
    for (const label of ["建造", "破坏", "机关与容器", "物品使用"]) assert(row.text.includes(label));
    const toggles = clickEvents(row.json, "建造：额外允许");
    assert(toggles.length);
    await chatCommand(toggles.at(-1).replace(/^\/?/, "/"), "建造：不额外授权");
    const updated = db.prepare('SELECT build,destroy,"switch",item_use FROM town_trust WHERE scope_type=\'TOWN\'').get();
    assert.deepEqual([updated.build, updated.destroy, updated.switch, updated.item_use], [0, 1, 1, 1]);
  });
  if (options.search) await runSearchScenarios({ bot, db, server, scenario, close, commandWindow, waitWindow,
    click, chatCommand, clickEvents, inventoryText, slots, itemText, messages });
} catch (error) {
  failure = error.stack ?? String(error);
  console.error(failure);
} finally {
  db?.close();
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
