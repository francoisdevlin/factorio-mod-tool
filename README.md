# Factorio Mod Tool

An async-first MCP server for Factorio mod development, built in ClojureScript targeting Node.js.

## What it does

Provides MCP tools for validating, linting, testing, and packaging Factorio mods — plus live interaction with running Factorio instances via RCON.

| Tool | Description |
|------|-------------|
| `validate-mod` | Validate info.json and mod directory structure |
| `parse-lua` | Parse Lua source into an AST |
| `lint-mod` | Run linting rules against a mod |
| `run-tests` | Execute mod unit tests (offline via busted) |
| `run-tests-live` | Execute in-game tests via RCON + FactorioTest |
| `pack-mod` | Bundle a mod into a distributable zip |
| `rcon-exec` | Execute a command on a live Factorio instance |
| `rcon-inspect` | Query game state from a live instance |

## Quick start

```bash
npm install
npx shadow-cljs compile server
node out/server.js
```

## Testing strategy

We use a two-tier approach:

- **Offline tests** with [busted](https://lunarmodules.github.io/busted/) for pure-logic Lua (fast, CI-friendly, no Factorio needed)
- **In-game tests** with [FactorioTest](https://github.com/GlassBricks/FactorioTest) for anything that touches real game APIs

### FactorioTest

[FactorioTest](https://github.com/GlassBricks/FactorioTest) by GlassBricks is an excellent testing framework that runs tests inside a real Factorio instance — no mocking necessary. It uses familiar `describe`/`it` syntax and gives you access to real entities, surfaces, and forces. If you're building Factorio mods and want actual confidence in your code, check it out.

## Stack

- **ClojureScript** + shadow-cljs + Node.js
- **metosin/mcp-toolkit** — MCP server framework
- **funcool/promesa** — Async/await primitives
- **luaparse** — Lua 5.2 AST parsing
- **rcon-client** — RCON protocol for Factorio
- **archiver** — Zip creation for mod packaging
