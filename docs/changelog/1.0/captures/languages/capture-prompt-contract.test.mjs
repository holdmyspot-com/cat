import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const directory = new URL("./", import.meta.url);
const checkoutPrompt = await readFile(new URL("checkout-price-calculator.prompt.txt", directory), "utf8");
const documentationPrompt = await readFile(new URL("documentation.prompt.txt", directory), "utf8");
const captureScript = await readFile(new URL("capture-source-pairs.sh", directory), "utf8");
const monetaryValidation = await readFile(new URL("validate-money-capture.mjs", directory), "utf8");
const renderer = await readFile(new URL("generate-rendered-captures.mjs", directory), "utf8");
const specification = await readFile(new URL("../../specification.md", directory), "utf8");

assert.equal(checkoutPrompt,
  "In [Language], implement a checkout-price calculator for one caller-supplied item price. The item price, discount rate, and tax rate are exact decimal strings in plain nonnegative decimal notation: digits with an optional decimal point, and no sign, exponent, whitespace, or separator. Discount and tax rates must be in the inclusive range [0, 1] and are decimal fractions, so \"0.1\" means 10%. Return the final total as an exact decimal string with exactly two decimal places, rounded using half-even rounding. The caller selects standard or expedited shipping. Apply the discount only to the item price. If expedited shipping is used, add a 15.00 fee after that discount and before tax.\n");
assert.doesNotMatch(checkoutPrompt, /CAT|rule|plugin|agent|library|dependency|representation|API|validation|comment/i,
  "the checkout prompt must state only caller-visible requirements");
assert.equal(documentationPrompt, "Document this code\n");
assert.match(captureScript, /documentation_prompt_template=.*documentation\.prompt\.txt/,
  "the capture script must read the canonical documentation prompt");
assert.match(captureScript, /cmp -s "\$\{money_rule_source\}" "\$\{runtime_money_rule\}"/,
  "the capture script must reject a runtime with a stale Money rule");
assert.match(captureScript, /claude_temporary_root="\$\{TMPDIR:-\/tmp\}\/.cat\/claude\/changelog-language-captures"/,
  "the capture script must keep Claude runs outside the checkout to avoid Bubblewrap ancestor Git mounts");
assert.match(captureScript, /\[ "\$\{harness\}" = claude \][\s\S]*run_temporary_root="\$\{claude_temporary_root\}"/,
  "the capture script must select the system temporary root for Claude runs");
assert.match(captureScript, /capture_pair\(\)[\s\S]*capture_one .* base .*staging_directory[\s\S]*capture_one .* cat .*staging_directory/,
  "the capture script must stage a complete Base/CAT pair before publishing it");
assert.match(captureScript, /capture-pair\.json[\s\S]*awaiting complete Base and CAT captures[\s\S]*withheld[\s\S]*published/s,
  "the capture pair must retain its terminal publication decision and reason");
assert.match(captureScript, /trap record_pair_exit EXIT[\s\S]*record_pair_signal HUP 129[\s\S]*record_pair_signal INT 130[\s\S]*record_pair_signal TERM 143/s,
  "the pair must record normal exits and catchable termination signals before leaving a nonterminal record");
assert.match(captureScript, /exitCode:[\s\S]*signal:/,
  "the pair record must retain the observed exit status or signal");
assert.match(captureScript, /validate-money-capture\.mjs.*\$\{runner\}.*\$\{runtime\}/s,
  "the TypeScript monetary gate must invoke an independent harness model");
const captureSupervisor = await readFile(new URL("capture-source.mjs", directory), "utf8");
assert.match(captureSupervisor, /capture-supervisor\.json/,
  "the source supervisor must retain its terminal lifecycle evidence");
assert.match(captureSupervisor, /state,/,
  "the source supervisor record must identify its terminal state");
assert.match(captureSupervisor, /runnerProcessId/,
  "the source supervisor record must identify the runner");
assert.match(captureSupervisor, /The runner recorded a completed turn/,
  "the source supervisor record must explain a completed capture");
assert.match(monetaryValidation, /complete final response must be exactly APPROVED/i,
  "the monetary reviewer must have an explicit accept/reject response contract");
assert.match(monetaryValidation, /sole caller-visible contract[\s\S]*Do not add[\s\S]*caller-visible requirements after generation/,
  "the monetary reviewer must not add a requirement absent from the generation task");
assert.doesNotMatch(monetaryValidation, /approximateParameters|exactDecimalStringContract|dependencyMatch/,
  "the monetary reviewer must not decide the monetary contract by source-pattern matching");
assert.match(captureScript, /start\(\)[\s\S]*"\$@" &/,
  "the capture scheduler must invoke the scheduled pair operation rather than prefixing another operation");
assert.match(renderer, /documentation\.prompt\.txt/,
  "the renderer must validate retained documentation prompts against the canonical prompt");
assert.match(specification, /checkout-price-calculator\.prompt\.txt/,
  "the specification must name the canonical checkout prompt");
assert.match(specification, /documentation\.prompt\.txt/,
  "the specification must name the canonical documentation prompt");
assert.match(specification, /publish all four artifacts for that pair only after both runs complete/,
  "the specification must require atomic Base/CAT pair publication");
