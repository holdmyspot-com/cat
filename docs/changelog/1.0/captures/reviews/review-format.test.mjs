import assert from "node:assert/strict";
import {mkdtemp, mkdir, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";

import {delegationPrompt, reviewFormat} from "./review-format.mjs";
import {validReviewResponse} from "./review-response.mjs";

const finding = "## Findings\n\n- **[CHANGE.md:1](CHANGE.md:1) Missing validation**\n";
const fields = ["Evidence", "Consequence", "Correction"];
const findingFields = (indent) => fields.map((field) => `${indent}- **${field}:** test finding.`).join("\n");

assert.ok(validReviewResponse(finding + findingFields("\t")), "The shipped Review Contract indents fields with tabs");
assert.ok(validReviewResponse(finding + findingFields("  ")), "The historical evidence spells the same nesting with spaces");
assert.equal(validReviewResponse(finding + findingFields("")), false, "Finding fields must remain nested");

const artifact = await mkdtemp(join(tmpdir(), "cat-review-format-"));
try
{
  const contract = "# Review Contract\n\nReturn exactly `APPROVED`.\n";
  await mkdir(join(artifact, "agents/common"), {recursive: true});
  await writeFile(join(artifact, "agents/common/review-contract.md"), contract);

  const loadedContract = await reviewFormat(artifact);

  assert.equal(loadedContract, contract.trim());
  assert.match(delegationPrompt("Review this change", loadedContract), new RegExp(contract.trim()));
}
finally
{
  await rm(artifact, {recursive: true, force: true});
}
