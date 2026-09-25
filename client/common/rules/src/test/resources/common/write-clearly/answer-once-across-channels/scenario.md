## Scenario

You are implementing a feature under a persistent goal. The user interrupts to ask why component C3 is being reopened.
The current plan labels C3 `candidate / UNVERIFIED`, with no integration or closure receipt. A separate C1→C9B plan
finding does not invalidate C3's passing focused tests. These facts already answer the question; no further check is
needed before replying. Your host permits commentary updates and a final answer.

Answer the user's question. The implementation goal should remain active afterward.

## Assertions

### complete-answer-once

The final answer explains that C3 was never closed and distinguishes the separate C1→C9B finding. The assistant does
not first give essentially that complete answer in commentary and then repeat it in final. A brief acknowledgement
without the answer is allowed.
