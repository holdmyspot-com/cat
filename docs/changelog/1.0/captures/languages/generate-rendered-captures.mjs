#!/usr/bin/env node
// Generates language-indexed changelog data only from completed Base and CAT source captures.
import {readFile, writeFile} from "node:fs/promises";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";

const directory = dirname(fileURLToPath(import.meta.url));
const checkoutPromptTemplate = await readFile(join(directory, "checkout-price-calculator.prompt.txt"), "utf8");
const documentationPromptTemplate = await readFile(join(directory, "documentation.prompt.txt"), "utf8");
if (/\b(?:CAT|rule|plugin|agent|library|dependency|representation|API|validation|comment)\b/i.test(checkoutPromptTemplate))
  throw new Error("checkout-price-calculator.prompt.txt must state only caller-visible requirements");
const languages = [
  {id: "typescript", label: "TypeScript", extension: "ts", code: "checkout-price-calculator.ts",
    documentation: "reservation-expiry.ts"},
  {id: "python", label: "Python", extension: "py", code: "checkout_price_calculator.py",
    documentation: "reservation_expiry.py"},
  {id: "java", label: "Java", extension: "java", code: "CheckoutPriceCalculator.java",
    documentation: "ReservationExpiry.java"},
  {id: "csharp", label: "C#", extension: "cs", code: "CheckoutPriceCalculator.cs",
    documentation: "ReservationExpiry.cs"},
  {id: "cpp", label: "C++", extension: "cpp", code: "CheckoutPriceCalculator.cpp",
    documentation: "ReservationExpiry.cpp"},
  {id: "php", label: "PHP", extension: "php", code: "CheckoutPriceCalculator.php",
    documentation: "ReservationExpiry.php"},
  {id: "go", label: "Go", extension: "go", code: "checkout_price_calculator.go",
    documentation: "reservation_expiry.go"}
];

function expectedPrompt(language, example)
{
  if (example === "documentation")
    return documentationPromptTemplate;
  return checkoutPromptTemplate.replaceAll("[Language]", language.label);
}

async function completedManifest(path, harness)
{
  const manifest = JSON.parse(await readFile(path, "utf8"));
  if (manifest.harness !== harness || manifest.state !== "completed" || manifest.exitCode !== 0 || manifest.turnNumber < 1)
    throw new Error(`${path} is not a completed ${harness} capture`);
  return manifest;
}

async function terminalEvent(path, harness)
{
  const events = (await readFile(path, "utf8")).trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const completed = harness === "claude" ? events.some((event) => event.type === "result" && !event.is_error) :
    events.some((event) => event.method === "turn/completed" && event.params?.turn?.status !== "failed");
  if (!completed)
    throw new Error(`${path} has no successful ${harness} terminal event`);
}

function unifiedDiff(basePath, catPath, filename)
{
  const result = spawnSync("git", ["diff", "--no-index", "--no-ext-diff", "--unified=999999", "--", basePath,
    catPath], {encoding: "utf8"});
  if (![0, 1].includes(result.status) || result.error)
    throw new Error(`Could not generate ${filename} diff: ${result.error?.message ?? result.stderr}`);
  return result.stdout.replace(basePath, filename).replace(catPath, filename);
}

const captures = {};
for (const language of languages)
{
  captures[language.id] = {label: language.label};
  for (const example of ["code", "documentation"])
  {
    const filename = language[example];
    const exampleDirectory = join(directory, language.id, example);
    captures[language.id][example] = {filename, prompt: expectedPrompt(language, example)};
    for (const harness of ["codex", "claude"])
    {
      const basePath = join(exampleDirectory, `${harness}-base.${language.extension}`);
      const catPath = join(exampleDirectory, `${harness}-cat.${language.extension}`);
      await Promise.all([
        completedManifest(join(exampleDirectory, `${harness}-base.run.json`), harness),
        completedManifest(join(exampleDirectory, `${harness}-cat.run.json`), harness),
        terminalEvent(join(exampleDirectory, `${harness}-base.events.jsonl`), harness),
        terminalEvent(join(exampleDirectory, `${harness}-cat.events.jsonl`), harness)
      ]);
      for (const mode of ["base", "cat"])
      {
        const prompt = await readFile(join(exampleDirectory, `${harness}-${mode}.prompt.txt`), "utf8");
        if (prompt !== expectedPrompt(language, example))
          throw new Error(`${language.id}/${example}/${harness}-${mode} did not retain the canonical prompt`);
      }
      captures[language.id][example][harness] = {
        base: await readFile(basePath, "utf8"),
        cat: await readFile(catPath, "utf8"),
        unifiedDiff: unifiedDiff(basePath, catPath, filename)
      };
    }
  }
}

await writeFile(join(directory, "rendered-captures.js"), `window.catLanguageCaptures = ${JSON.stringify(captures)};\n`);
