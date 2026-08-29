#!/usr/bin/env node
// Retains one or two Claude parent turns and accepts only a contract-conforming delegated review.
import {appendFile, readFile, writeFile} from "node:fs/promises";
import {spawn} from "node:child_process";
import {createInterface} from "node:readline";
import {delegationPrompt, reviewFormat} from "./review-format.mjs";

const [runner, artifact, project, promptFile, output, runRoot, reviewer] = process.argv.slice(2);
if ([runner, artifact, project, promptFile, output, runRoot, reviewer].some((value) => !value))
	throw new Error("Usage: capture-claude-review.mjs <runner> <artifact> <project> <prompt-file> <output> <run-root> <reviewer>");
const valid = (response) => response?.trim() === "APPROVED" || (response?.split("\n").find(line => line.trim()) === "## Findings" &&
  (matches => matches.length > 0 && matches.every((match, index) => {
    const finding = response.slice(match.index, matches[index + 1]?.index);
    return ["Evidence", "Consequence", "Correction"].every(field => new RegExp(`^  - \\*\\*${field}:\\*\\*`, "m").test(finding));
  }))([...response.matchAll(/^- \*\*\[[^\]]+\]\([^\s)]+\)/gm)]));
const initial = await readFile(promptFile, "utf8");
const reviewContract = await reviewFormat(artifact);
await writeFile(output, "");
const child = spawn(runner, ["--artifact", artifact, "--project", project, "--run-root", runRoot, "--keep-run", "--install-cat"], {stdio: ["pipe", "pipe", "pipe"]});
const keepAlive = setInterval(() => {}, 1_000); let error = ""; let started; const ready = new Promise(resolve => { started = resolve; }); let complete; const turns = []; let writes = Promise.resolve();
createInterface({input: child.stdout}).on("line", line => { const message = JSON.parse(line); if (message.type === "started") started(); if (message.type === "native-event") writes = writes.then(() => appendFile(output, `${message.event}\n`)); if (message.type === "turn-completed") { if (complete) { complete(message); complete = null; } else turns.push(message); } });
child.stderr.on("data", chunk => { error += chunk; });
const send = async (prompt) => { child.stdin.write(`${JSON.stringify({type: "turn", prompt})}\n`); return turns.shift() ?? new Promise(resolve => { complete = resolve; }); };
await ready; await send(delegationPrompt(initial, reviewContract)); await writes;
let events = (await readFile(output, "utf8")).trim().split("\n").filter(Boolean).map(JSON.parse);
let response = events.filter(event => event.type === "result").at(-1)?.result;
if (!valid(response)) { await send(delegationPrompt("The reviewer response you just relayed did not meet its required format. Delegate a fresh review to the same installed CAT reviewer, include this complete Review Contract in the child task, and return that response without rewriting it.", reviewContract)); await writes; events = (await readFile(output, "utf8")).trim().split("\n").filter(Boolean).map(JSON.parse); response = events.filter(event => event.type === "result").at(-1)?.result; }
child.stdin.write(`${JSON.stringify({type: "complete"})}\n`); const code = await new Promise(resolve => child.once("exit", resolve)); clearInterval(keepAlive);
if (code !== 0 || !valid(response)) throw new Error(`Claude reviewer capture did not satisfy its injected contract. ${error}`.trim());
