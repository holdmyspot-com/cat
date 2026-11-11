#!/usr/bin/env python3
"""Starts and reports the CI runs of this repository on its hosted runners."""
import json, os, sys, time

DURATION = 50
RUNNERS = 2
BRANCHES = {"fix-dates": None, "fix-locale": "{{failing_test}}", "fix-retry": None}
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".ci", "runs.json")


def load():
	if not os.path.exists(STATE):
		return []
	with open(STATE) as f:
		return json.load(f)


def schedule(runs, now):
	free_at = [0.0] * RUNNERS
	for run in runs:
		slot = min(range(RUNNERS), key=lambda i: free_at[i])
		run["begin"] = max(run["requested"], free_at[slot])
		free_at[slot] = run["begin"] + DURATION
		run["end"] = free_at[slot]
		if now < run["begin"]:
			run["state"] = "queued"
		elif now < run["end"]:
			run["state"] = "running"
		else:
			run["state"] = "failed" if BRANCHES[run["branch"]] else "passed"
	return runs


def usage():
	print("usage: ci.sh start <branch> | status | log <run> | limits")
	print("branches: " + ", ".join(BRANCHES))
	sys.exit(2)


def main():
	if len(sys.argv) < 2:
		usage()
	now = time.time()
	runs = load()
	command = sys.argv[1]
	if command == "limits":
		print("repository visibility: public")
		print("runners: shared runners, free of charge for this repository")
		print("concurrent jobs allowed: %d" % RUNNERS)
	elif command == "start" and len(sys.argv) == 3:
		branch = sys.argv[2]
		if branch not in BRANCHES:
			print("unknown branch: " + branch, file=sys.stderr)
			sys.exit(2)
		runs.append({"id": len(runs) + 1, "branch": branch, "requested": now})
		os.makedirs(os.path.dirname(STATE), exist_ok=True)
		with open(STATE, "w") as f:
			json.dump(runs, f)
		run = schedule(runs, now)[-1]
		if run["state"] == "queued":
			print("run %d: %s queued, all %d runners are busy" % (run["id"], branch, RUNNERS))
		else:
			print("run %d: %s started" % (run["id"], branch))
	elif command == "status" and len(sys.argv) == 2:
		for run in schedule(runs, now):
			print("run %d: %s: %s" % (run["id"], run["branch"], run["state"]))
	elif command == "log" and len(sys.argv) == 3:
		matching = [r for r in schedule(runs, now) if str(r["id"]) == sys.argv[2]]
		if not matching:
			print("unknown run: " + sys.argv[2], file=sys.stderr)
			sys.exit(2)
		run = matching[0]
		if run["state"] not in ("passed", "failed"):
			print("log not available: the run has not finished", file=sys.stderr)
			sys.exit(1)
		if BRANCHES[run["branch"]]:
			print("FAILED " + BRANCHES[run["branch"]])
		else:
			print("all tests passed")
	else:
		usage()


main()
