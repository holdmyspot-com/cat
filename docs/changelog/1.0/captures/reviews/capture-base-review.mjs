#!/usr/bin/env node
// Retains one neutral Base review under the requested harness and model profile.
import {captureBaseReview} from "./capture-review.mjs";

const [harness, ...args] = process.argv.slice(2);
await captureBaseReview(harness, args);
