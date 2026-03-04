# Factorio Mod Tool — Refinery Rig

Async-first MCP server for Factorio mod development, built in ClojureScript targeting Node.js.

## Build & Run

```bash
cd refinery/rig

# Install dependencies
npm install

# Compile
npx shadow-cljs compile server

# Run the MCP server (stdio transport)
node out/server.js

# Run tests
npx shadow-cljs compile test

# Watch mode (auto-rebuild on save)
npx shadow-cljs watch server
npx shadow-cljs watch test
```

## Architecture

```
                  ┌─────────────────┐
                  │   MCP Client    │
                  │ (Claude Code)   │
                  └────────┬────────┘
                           │ stdio (JSON-RPC)
                  ┌────────▼────────┐
                  │     server.cljs  │  Entry point, transport, tool registration
                  │     state.cljs   │  Atoms: mod-state, rcon-connections
                  └────────┬────────┘
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
    │  analysis/  │ │   util/     │ │   rcon/     │
    │  lint.cljs  │ │  lua.cljs   │ │  client.cljs│
    │ validate.cljs│ │  mod.cljs   │ └─────────────┘
    └─────────────┘ │  fs.cljs    │
                    └─────────────┘
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────▼──────┐ ┌──────▼──────┐
    │  testing/   │ │  bundle/    │
    │ harness.cljs│ │  pack.cljs  │
    └─────────────┘ └─────────────┘
```

### Key Libraries

| Library | Purpose |
|---------|---------|
| `metosin/mcp-toolkit` | MCP server framework (CLJC, idiomatic) |
| `funcool/promesa` | Async/await primitives for CLJS |
| `luaparse` (npm) | Lua 5.2 AST parsing |
| `rcon-client` (npm) | RCON protocol for Factorio |
| `archiver` (npm) | Zip creation for mod packaging |

### MCP Tools

| Tool | Status | Description |
|------|--------|-------------|
| `validate-mod` | Scaffolded | Validate info.json + mod structure |
| `parse-lua` | Scaffolded | Parse Lua source → AST |
| `lint-mod` | Stub | Run linting rules |
| `run-tests` | Stub | Execute mod unit tests |
| `pack-mod` | Stub | Bundle mod into zip |
| `rcon-exec` | Stub | Execute RCON command |
| `rcon-inspect` | Stub | Query game state |

### Async Pattern

All I/O returns Promesa promises. Use `p/let` for sequential async, `p/all` for parallel.

```clojure
(p/let [info (mod/read-info-json path)
        errors (validate/validate-info info)]
  {:valid? (empty? errors) :errors errors})
```

### State Management

Two atoms in `state.cljs`:
- `mod-state` — Map of mod paths → parsed state (info, files, diagnostics)
- `rcon-connections` — Map of instance names → active RCON connections

## Conventions

- **Async-first**: All I/O functions return promises
- **Data-oriented**: Prefer plain maps over objects
- **Stubs return promises**: Even stub functions return `(p/resolved ...)` for consistent interfaces
- **Tests use `async` + `done`**: Required for promise-based cljs.test assertions
