import {spawn} from "node:child_process";
import {open} from "node:fs/promises";
import {setTimeout} from "node:timers/promises";

const recordReadSize = 64 * 1024;
const waitBetweenReads = 50;

/**
 * Starts the selected runner with closed stdin and waits for its exit while keeping its diagnostics visible.
 */
export function startRunner(runner, args)
{
  const child = spawn(runner, args, {stdio: ["ignore", "ignore", "inherit"]});
  let result;
  const closed = new Promise((resolve) =>
  {
    child.once("error", (error) => { result = {error}; });
    child.once("close", (code, signal) =>
    {
      result ??= {code, signal};
      resolve();
    });
  });
  return {
    get finished() { return result !== undefined; },
    closed,
    stop() { child.kill(); },
    async requireSuccess()
    {
      await closed;
      if (result.error)
        throw result.error;
      if (result.code !== 0)
        throw new Error(`${runner} ended with ${result.signal ?? `exit status ${result.code}`}`);
    }
  };
}

/**
 * Yields complete JSONL records with their next byte positions. With a runner, tails the file through runner exit.
 */
export async function* readRecords(path, {launch, start = 0} = {})
{
  let file;
  while (file === undefined)
  {
    const finished = launch?.finished;
    try
    {
      file = await open(path);
    }
    catch (error)
    {
      if (error.code !== "ENOENT" || launch === undefined)
        throw error;
      if (finished)
        return;
      await setTimeout(waitBetweenReads);
    }
  }

  const buffer = Buffer.alloc(recordReadSize);
  let position = start;
  let partial = Buffer.alloc(0);
  try
  {
    while (true)
    {
      const finished = launch === undefined || launch.finished;
      const {bytesRead} = await file.read(buffer, 0, buffer.length, position);
      if (bytesRead === 0)
      {
        if (finished)
          break;
        await setTimeout(waitBetweenReads);
        continue;
      }

      position += bytesRead;
      const chunk = Buffer.concat([partial, buffer.subarray(0, bytesRead)]);
      let offset = 0;
      for (let newline = chunk.indexOf(10); newline !== -1; newline = chunk.indexOf(10, offset))
      {
        const line = chunk.toString("utf8", offset, newline);
        offset = newline + 1;
        if (line.trim())
          yield {record: JSON.parse(line), position: position - chunk.length + offset};
      }
      partial = chunk.subarray(offset);
    }
    if (partial.length > 0)
      throw new Error(`${path} ended with an incomplete JSONL record`);
  }
  finally
  {
    await file.close();
  }
}
