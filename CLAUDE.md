# CLAUDE.md

Guidance for working in this repo. Project background and scope live in [README.md](README.md).

## Project

GridLoad: a web app that shows whether now is a good time to run household appliances, based on dynamic electricity prices.

## Stack

- TypeScript + HTML. Keep it minimal; no framework, backend or database unless asked.

## Current task scope

Very basic MVP: a single field showing red / orange / green.
- Red = not good to run appliances
- Orange = run only if you must
- Green = run now!

Do not add appliance management, optimization, or persistence yet. The user will give further instructions step by step.

## Data source

`https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise`

The response schema and the price thresholds for red/orange/green are not yet documented here. Inspect the real response before writing the parsing code, and record the schema and chosen thresholds in this file.

## Conventions

- Commit only when asked; the user pushes.
