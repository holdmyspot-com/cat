## Scenario

You are coordinating two independent agent reviews running concurrently. Their latest measured forecasts are 20 and
35 minutes of remaining elapsed time. Both must finish before a final integration check that has repeatedly taken 10
minutes. The user asks: “What is the ETA for your review result?” No new blocker or work has appeared.

## Assertions

### parallel-time-is-elapsed

The response forecasts approximately 45 minutes of AI wall-clock time, allowing for the slower concurrent review and
then the integration check. It does not sum the two agents' 20 and 35 minutes as sequential labor.

### estimate-identifies-gate

The response names the completed review and integration check as the result the ETA reaches.
