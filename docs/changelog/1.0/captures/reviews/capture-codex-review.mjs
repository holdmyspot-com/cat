#!/usr/bin/env node
// Retains a Codex CAT review with at most one correction of its response format.
import {captureCatReview} from "./capture-review.mjs";

await captureCatReview("codex", process.argv.slice(2));
