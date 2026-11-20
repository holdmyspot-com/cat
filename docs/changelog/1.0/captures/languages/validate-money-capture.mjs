#!/usr/bin/env node
// Runs an independent model review of a generated TypeScript monetary capture and retains its raw verdict.
import {copyFile, mkdir, readFile, writeFile} from "node:fs/promises";
import {isAbsolute, join} from "node:path";
import {captureSource, captureTimeoutSeconds} from "./capture-source.mjs";

const [runner, harness, project, taskFile, output, resultsFolder, model, effort] = process.argv.slice(2);
if ([runner, project, taskFile, output, resultsFolder, model, effort].some((value) => !value?.trim()) ||
  !isAbsolute(runner) || !["codex", "claude"].includes(harness))
{
  throw new Error("Usage: validate-money-capture.mjs <absolute runner> <codex|claude> <project> <task-file> " +
    "<events-output> <results> <model> <effort>");
}
const timeoutSeconds = captureTimeoutSeconds(process.env.CAPTURE_TIMEOUT_SECONDS);

const task = await readFile(taskFile, "utf8");
const prompt = `
Review the generated TypeScript checkout-price calculator in this project. Do not edit any file.

The following is the complete generation task. It is the sole caller-visible contract for this review:

--- task begins ---
${task}--- task ends ---

Accept it only when every task requirement is met and it uses a declared established exact-decimal or money dependency
rather than binary floating point or custom decimal, rational, or bigint monetary arithmetic. Inspect the source and any
package manifest or lockfile needed to support the conclusion. Do not require or reject an API shape, parameter or
import name, representation, validation policy, or behavior that the generation task does not require. Do not add
caller-visible requirements after generation. Your complete final response must be exactly APPROVED, or exactly
REJECTED: followed by one concise, evidence-based reason.
`;
const promptFile = `${output}.prompt.txt`;
await writeFile(promptFile, prompt);

const evidenceProject = join(resultsFolder, "project");
await mkdir(evidenceProject, {recursive: true});
await copyEvidence(project, evidenceProject);
const {assistantResponse: response} = await captureSource(
  {runner, harness, project: evidenceProject, promptFile, output, resultsFolder, mode: "base", model, effort},
  timeoutSeconds);
if (response?.trim() !== "APPROVED")
  throw new Error(`TypeScript monetary model validation rejected the capture: ${response?.trim() ?? "no final response"}`);

async function copyEvidence(sourceDirectory, destinationDirectory)
{
  for (const filename of ["checkout-price-calculator.ts", "package.json", "package-lock.json", "npm-shrinkwrap.json",
    "pnpm-lock.yaml", "yarn.lock"])
  {
    try
    {
      await copyFile(join(sourceDirectory, filename), join(destinationDirectory, filename));
    }
    catch (error)
    {
      if (error.code !== "ENOENT")
        throw error;
    }
  }
}
