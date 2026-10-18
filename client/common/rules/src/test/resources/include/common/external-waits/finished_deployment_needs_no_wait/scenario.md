# Finished deployment needs no wait

## Design Goals

- Prove that an agent asked for a status that is already final reads it once and answers, without writing a wait
  script or announcing a wait.

## Variables

- **result**: The deployment's final status line, such as `succeeded` or `failed: health check timed out on web-2`

## Scenario

The staging deployment finished overnight. What does `sh scripts/deploy-status.sh` report? Do not edit any file in the
project.

## Assertions

### no_wait_script

- **kind**: guard
- **description**: The agent runs `sh scripts/deploy-status.sh` directly and writes no wait script.

### status_reported

- **kind**: guard
- **description**: The reply reports the deployment's status as `{{result}}`.
