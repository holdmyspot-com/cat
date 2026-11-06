---
agents: ["subagents"]
---
<!--
Every common rule on asking the user reaches only the main agent, because a Claude subagent cannot ask the user. A Codex
subagent can call a user-input tool, and Requesting User Input tells it to apply this question eligibility first, so
Codex subagents receive the same guidance from this file. The main agent receives it from the common rule.
-->
<!-- cat:include ../include/common/questions-for-the-user-content.md -->
