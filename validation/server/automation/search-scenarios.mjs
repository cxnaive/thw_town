import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { until } from "./harness.mjs";

export async function runSearchScenarios({ bot, db, server, scenario, close, commandWindow, waitWindow,
  click, chatCommand, clickEvents, inventoryText, slots, itemText, messages }) {
  function insert(table, row) {
    const copy = { ...row };
    delete copy.id;
    const keys = Object.keys(copy);
    return Number(db.prepare(`INSERT INTO ${table} (${keys.map(key => '"' + key + '"').join(",")}) VALUES (${keys.map(() => "?").join(",")})`)
      .run(...keys.map(key => copy[key])).lastInsertRowid);
  }
  function nameOf(item) {
    const component = item?.components?.find(part => part.type === "custom_name")?.data;
    function text(node) {
      if (!node || typeof node !== "object") return typeof node === "string" ? node : "";
      if (node.type) return text(node.value);
      if (Array.isArray(node)) return node.map(text).join("");
      return text(node.text) + text(node.extra);
    }
    return text(component);
  }
  const landNames = () => slots().filter(item => item?.name === "filled_map").map(nameOf);
  const townNames = () => slots().filter(item => item?.name === "grass_block").map(nameOf);
  async function submitInput(keyword, title) {
    await until("search input closes inventory", () => !bot.currentWindow);
    bot.chat(keyword);
    await waitWindow(title);
  }
  const template = db.prepare("SELECT * FROM dominion WHERE name='手册测试镇'").get();
  const town = db.prepare("SELECT * FROM town WHERE id=?").get(template.town_id);
  const other = "10000000-0000-0000-0000-000000000001";
  for (const name of ["黄金城", "黄金城市", "新黄金城区", "黄河金色城"]) {
    insert("town", { ...town, name, mayor_uuid: other });
  }
  // A normal write-through operation reloads the town cache, including our isolated fixtures.
  await close();
  await chatCommand("/town rename 手册搜索镇", "已重命名");
  const ownNames = ["黄金城", ...Array.from({ length: 25 }, (_, i) => `黄金城${String(i + 1).padStart(2, "0")}`), "IronFarm"];
  async function loadFixture(name) {
    // Fixture loading uses the test server console so it does not fill the bot's chat-spam counter.
    server.command(`dom rename ${name}临时 ${name}`);
    await until(`fixture ${name}`, () => db.prepare("SELECT id FROM dominion WHERE name=?").get(name));
    await delay(100);
  }
  for (const [i, name] of ownNames.entries()) {
    insert("dominion", { ...template, name: name + "临时", claim_kind: "NORMAL",
      x1: template.x1 + (i + 10) * 1000, x2: template.x2 + (i + 10) * 1000 });
    await loadFixture(name);
  }
  insert("dominion", { ...template, name: "黄金城私有临时", owner: other, town_id: null,
    claim_kind: "NORMAL", x1: 50000, x2: 50100 });
  await loadFixture("黄金城私有");
  const remoteServer = 257;
  db.prepare("INSERT INTO server_info(id,name) VALUES(?,?)").run(remoteServer, "lobby3");
  db.prepare("INSERT INTO world_map(server_id,name,uid) VALUES(?,?,?)").run(remoteServer, "remote_world", template.world_uid);
  insert("dominion", { ...template, name: "远端黄金城", owner: other, server_id: remoteServer, claim_kind: "NORMAL" });
  server.command("deop TownGuideQA");
  await delay(300);
  const menu = await chatCommand("/dom menu", "切换箱子菜单");
  const switchUI = menu.flatMap(msg => clickEvents(msg.json, "切换箱子菜单"));
  assert(switchUI.length);
  bot.chat(switchUI.at(-1).replace(/^\/?/, "/"));
  await waitWindow("Dominion");

  await scenario("town search ranks literal and fuzzy matches and supports list input", async () => {
    await commandWindow("/town search 黄金城", "搜索小镇");
    assert.deepEqual(townNames(), ["黄金城", "黄金城市", "新黄金城区", "黄河金色城"]);
    await click("清除搜索", "小镇列表");
    await click("搜索");
    await submitInput("黄城", "搜索小镇");
    assert.equal(townNames().length, 4);
    const exact = slots().findIndex(item => nameOf(item) === "黄金城");
    assert(exact >= 0);
    await bot.clickWindow(exact, 0, 0);
    await waitWindow("小镇：黄金城");
  });

  await scenario("personal land search is scoped and preserves results across GUI pages", async () => {
    await commandWindow("/dom search 黄金城", "搜索领地");
    assert.equal(landNames()[0], "黄金城", "Exact name first");
    assert(inventoryText().includes("找到 27 项"));
    assert(!landNames().includes("黄金城私有"), "Another player's personal land stays outside this scope");
    const first = landNames();
    assert.equal(first.length, 21);
    await click("下一页", "搜索领地");
    const second = landNames();
    assert.equal(second.length, 6);
    assert.equal(new Set([...first, ...second]).size, 27, "No duplicate or lost results while paging");
    assert(second.includes("远端黄金城"), "Managed outposts on other servers are searchable");
    await click("上一页", "搜索领地");
    assert.deepEqual(landNames(), first);
  });

  await scenario("case, width, multiple words, empty results, cancel and length limits", async () => {
    await commandWindow("/dom list", "领地列表");
    await click("搜索");
    await submitInput("ＩＲＯＮ", "搜索领地");
    assert.deepEqual(landNames(), ["IronFarm"]);
    await click("修改关键词");
    await submitInput("黄金 25", "搜索领地");
    assert.deepEqual(landNames(), ["黄金城25"]);
    await click("修改关键词");
    await submitInput("找不到的名称", "搜索领地");
    assert.equal(landNames().length, 0);
    assert(inventoryText().includes("没有找到匹配"));
    await click("修改关键词");
    await until("input open", () => !bot.currentWindow);
    bot.chat("C");
    await waitWindow("领地列表");
    await close();
    await chatCommand(`/dom search ${"字".repeat(65)}`, "最多 64");
    await commandWindow("/dom search %", "搜索领地");
    assert.equal(landNames().length, 0, "SQL wildcard is a literal");
    await commandWindow("/dom search 黄金城 2147483647", "搜索领地");
    assert.equal(landNames().length, 6, "Huge page number clamps to the last page");
  });

  await scenario("town outpost search keeps scope and explains remote actions", async () => {
    await commandWindow("/town help land", "管理小镇属地");
    await click("打开属地列表", "的小镇属地");
    await click("搜索");
    await submitInput("远端", "搜索领地");
    assert.deepEqual(landNames(), ["远端黄金城"]);
    assert(inventoryText().includes("手册搜索镇"));
    assert(inventoryText().includes("红石区") && inventoryText().includes("remote_world"));
    const begin = messages.length;
    await click("远端黄金城");
    // The left click remains on the results and provides a location hint; it must not try to load that world.
    await until("remote location hint", () => messages.slice(begin).some(msg => msg.text.includes("管理请先前往")));
    assert.deepEqual(landNames(), ["远端黄金城"]);
  });

  await scenario("search action rechecks a removed result before opening it", async () => {
    await commandWindow("/dom search IronFarm", "搜索领地");
    assert.deepEqual(landNames(), ["IronFarm"]);
    db.prepare("DELETE FROM dominion WHERE name='IronFarm'").run();
    const begin = messages.length;
    await click("IronFarm");
    await until("removed result hint", () => messages.slice(begin).some(msg => msg.text.includes("这条结果已不存在")));
    assert.equal(landNames().length, 1, "A stale result stays visible until the next query, without opening management");
  });

  await scenario("administrator list search requires administrator access", async () => {
    server.command("op TownGuideQA");
    await delay(300);
    await commandWindow("/dom list_all", "本服务器全部领地");
    await click("搜索");
    await submitInput("黄金城私有", "搜索领地");
    assert.deepEqual(landNames(), ["黄金城私有"]);
    server.command("deop TownGuideQA");
    await delay(300);
    const begin = messages.length;
    await click("黄金城私有");
    await until("admin access denied", () => messages.slice(begin).some(msg => msg.text.includes("dominion.admin")));
    assert.deepEqual(landNames(), ["黄金城私有"], "Revoked admins cannot open management from a stale search result");
  });

  await scenario("TUI search retains keyword on real next-page links", async () => {
    await commandWindow("/dom menu", "Dominion");
    await click("切换聊天菜单");
    await until("TUI switched", () => !bot.currentWindow);
    const first = await chatCommand("/dom search 黄金城", "搜索领地");
    assert(first.some(msg => msg.text.includes("找到 27 项")));
    const next = first.flatMap(msg => clickEvents(msg.json, "▶"));
    assert(next.length);
    const second = await chatCommand(next.at(-1).replace(/^\/?/, "/"), "搜索领地");
    assert(second.some(msg => msg.text.includes("[2/4]")));
    assert(second.some(msg => msg.text.includes("关键词：黄金城")));
    const clear = second.flatMap(msg => clickEvents(msg.json, "清除搜索"));
    await chatCommand(clear.at(-1).replace(/^\/?/, "/"), "你的领地列表");
    const towns = await chatCommand("/town search 黄城", "搜索小镇");
    assert(towns.some(msg => msg.text.includes("找到 4 项")));
    assert(!bot.currentWindow);
  });
}
