// Generates language-indexed review-panel data directly from retained raw event streams.
import {readFile, writeFile} from "node:fs/promises";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

const directory = dirname(fileURLToPath(import.meta.url));
const capturesDirectory = join(directory, "languages");
const reviewTaskTemplate = await readFile(join(directory, "review-task-template.txt"), "utf8");
const semanticCorrespondence = JSON.parse(await readFile(join(directory, "semantic-correspondence.json"), "utf8"));
const languages = ["typescript", "python", "java", "csharp", "cpp", "php", "go"];
const categories = [
  ["architecture", "Architecture"], ["business", "Business"], ["deployment", "Deployment"],
  ["design", "Design"], ["legal", "Legal"], ["performance", "Performance"],
  ["requirements", "Requirements"], ["security", "Security"], ["testing", "Testing"], ["ux", "UX"],
  ["plan-review", "Plan Review"]
];

if (!reviewTaskTemplate.includes("[Concern]") || /CAT|delegate|reviewer/i.test(reviewTaskTemplate))
  throw new Error("review-task-template.txt must be a neutral task template with a concern placeholder");

async function terminalResponse(harness, filename)
{
  const events = (await readFile(filename, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
  if (harness === "claude")
  {
    const response = events.filter((event) => event.type === "result").at(-1)?.result;
    if (typeof response !== "string" || !response.trim())
      throw new Error(`${filename} has no Claude terminal response`);
    return response;
  }
  const response = events.filter((event) => event.method === "turn/completed").map((event) =>
    event.params?.turn?.items).flat().filter((item) => item?.type === "agentMessage" &&
    item.phase === "final_answer").at(-1)?.text;
  if (typeof response !== "string" || !response.trim())
    throw new Error(`${filename} has no Codex terminal response`);
  return response;
}

function expectedReviewTask(category)
{
  return reviewTaskTemplate.replaceAll("[Concern]", category);
}

async function verifyCommonTask(language, harness, slug, category)
{
  const expected = expectedReviewTask(category);
  for (const mode of ["base", "cat"])
  {
    const filename = join(capturesDirectory, language, `${harness}-${mode}-${slug}.request.md`);
    if (await readFile(filename, "utf8") !== expected)
      throw new Error(`${filename} did not retain the common neutral task`);
  }
}

function validateCatResponse(response, filename)
{
  const first = response.split("\n").find((line) => line.trim());
  if (response.trim() === "APPROVED")
    return;
  if (first !== "## Findings")
    throw new Error(`${filename} does not begin with ## Findings`);
  const lines = response.split("\n");
  const findings = lines.map((line, index) => ({line, index})).filter(({line}) =>
    /^- \*\*\[[^\]]+\]\([^\s)]+\)/.test(line));
  if (findings.length === 0 || findings.length !== lines.filter((line) => /^- \*\*/.test(line)).length)
    throw new Error(`${filename} has a finding without a complete Markdown file link in its title`);
  for (const [{index}, findingIndex] of findings.map((finding, index) => [finding, index]))
  {
    const next = findings[findingIndex + 1]?.index ?? lines.length;
    const finding = lines.slice(index, next).join("\n");
    for (const field of ["Evidence", "Consequence", "Correction"])
    {
      if (!new RegExp(`^  - \\*\\*${field}:\\*\\*`, "m").test(finding))
        throw new Error(`${filename} has a finding that omits ${field}`);
    }
  }
}

function displayOrder(response, correspondence)
{
  const lines = response.split("\n");
  const starts = lines.map((line, index) => index).filter((index) => /^- \*\*/.test(lines[index]));
  if (starts.length === 0)
    return {response, correspondence};
  const heading = lines.slice(0, starts[0]);
  const findings = starts.map((start, index) => lines.slice(start, starts[index + 1] ?? lines.length));
  const ordered = findings.map((finding, index) => ({finding, index, baseIndex: correspondence[String(index + 1)]})).sort((left, right) =>
  {
    if (left.baseIndex !== undefined && right.baseIndex !== undefined)
      return left.baseIndex - right.baseIndex;
    if (left.baseIndex !== undefined)
      return -1;
    if (right.baseIndex !== undefined)
      return 1;
    return left.index - right.index;
  });
  return {response: [...heading, ...ordered.flatMap(({finding}) => finding)].join("\n"),
    correspondence: Object.fromEntries(ordered.map(({baseIndex}, index) => [index + 1, baseIndex]).filter(([, baseIndex]) =>
      baseIndex !== undefined))};
}

const captures = {};
const displayCorrespondence = {};
for (const language of languages)
{
  captures[language] = {};
  displayCorrespondence[language] = {};
  for (const harness of ["codex", "claude"])
  {
    captures[language][harness] = {};
    displayCorrespondence[language][harness] = {};
    for (const [slug, category] of categories)
    {
      await verifyCommonTask(language, harness, slug, category);
      const base = await terminalResponse(harness, join(capturesDirectory, language, `${harness}-base-${slug}.events.jsonl`));
      const filename = join(capturesDirectory, language, `${harness}-cat-${slug}.events.jsonl`);
      const cat = await terminalResponse(harness, filename);
      validateCatResponse(cat, filename);
      const ordered = displayOrder(cat, semanticCorrespondence[language]?.[harness]?.[category] ?? {});
      captures[language][harness][category] = {base, cat: ordered.response};
      displayCorrespondence[language][harness][category] = ordered.correspondence;
    }
  }
}

await writeFile(join(directory, "rendered-captures.js"),
  `window.catReviewRequestTemplate = ${JSON.stringify(reviewTaskTemplate)};\n` +
  `window.catReviewCorrespondence = ${JSON.stringify(displayCorrespondence)};\n` +
  `window.catReviewCaptures = ${JSON.stringify(captures)};\n`);
