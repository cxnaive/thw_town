import { spawn } from "node:child_process";
import { appendFileSync } from "node:fs";
import { createServer } from "node:net";
import { setTimeout as delay } from "node:timers/promises";

export function logIssues(logs) {
  return logs
    .replace(/\u001b\[[0-9;]*m/g, "")
    .split("\n")
    .filter(
      (line) =>
        /\bERROR\b|Exception|thread.*(?:check|violation)|watchdog.*timeout/i.test(
          line,
        ) ||
        /\bWARN\b.*\[Dominion\]/.test(
          line,
        ),
    );
}

export async function bounded(
  label,
  action,
  { timeout = 15_000, signal } = {},
) {
  signal?.throwIfAborted();
  let timer, onAbort;
  const limit = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), timeout);
    onAbort = () => reject(signal.reason);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
  try {
    return await Promise.race([action(), limit]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
  }
}

export async function until(
  label,
  condition,
  { timeout = 15_000, interval = 50, signal } = {},
) {
  const deadline = performance.now() + timeout;
  while (true) {
    signal?.throwIfAborted();
    const result = await condition();
    if (result) return result;
    if (performance.now() >= deadline)
      throw new Error(`Timed out: ${label} (${timeout}ms)`);
    await delay(interval, undefined, { signal });
  }
}

export async function assertPortFree(port) {
  const socket = createServer();
  await new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.listen({ host: "127.0.0.1", port, exclusive: true }, resolve);
  });
  await new Promise((resolve, reject) =>
    socket.close((error) => (error ? reject(error) : resolve())),
  );
}

// The runner owns this child only. A busy port never causes an existing server to be stopped.
export function managedProcess(
  command,
  args,
  { cwd, log, shutdownTimeout = 60_000 },
) {
  const child = spawn(command, args, { cwd, stdio: ["pipe", "pipe", "pipe"] });
  const state = { tail: "", error: null, closed: false, exit: null };
  child.on("error", (error) => {
    state.error = error;
  });
  child.stdin.on("error", (error) => {
    state.error ??= error;
  });
  const receive = (chunk) => {
    const value = chunk.toString();
    state.tail = (state.tail + value).slice(-2_000_000);
    try {
      appendFileSync(log, value);
    } catch (error) {
      state.error ??= error;
    }
  };
  child.stdout.on("data", receive);
  child.stderr.on("data", receive);
  child.once("close", (code, signal) => {
    state.closed = true;
    state.exit = { code, signal };
  });
  const check = () => {
    if (state.error) throw state.error;
    if (state.closed)
      throw new Error(`Child exited early: ${JSON.stringify(state.exit)}`);
  };
  return {
    child,
    state,
    check,
    command(line) {
      check();
      if (/[\r\n]/.test(line)) throw new Error("Expected one console command");
      child.stdin.write(`${line}\n`);
    },
    async stop() {
      let forced = false;
      if (!state.closed) {
        if (child.stdin.writable) child.stdin.write("stop\n");
        try {
          await until("normal server shutdown", () => state.closed, {
            timeout: shutdownTimeout,
          });
        } catch {
          forced = true;
          child.kill("SIGTERM");
          try {
            await until("terminated server exit", () => state.closed, {
              timeout: 5_000,
            });
          } catch {
            child.kill("SIGKILL");
            await until("killed server exit", () => state.closed, {
              timeout: 5_000,
            });
          }
        }
      }
      return { ...state.exit, forced, error: state.error?.message };
    },
  };
}
