# Quickstart: Factorio Mod Tool

This guide walks you through building and using the Factorio Mod Tool CLI.
By the end, you'll have validated a mod directory and parsed both valid and
invalid Lua — confirming the tool works correctly.

## Prerequisites

- [Node.js](https://nodejs.org/) (18+)
- npm (comes with Node.js)

Verify your setup:

```bash
node --version
npm --version
```

## 1. Build the CLI

Clone the repository and build:

```bash
git clone https://github.com/francoisdevlin/factorio-mod-tool.git
cd factorio-mod-tool
npm install
npx shadow-cljs compile cli
```

The CLI is now available at `out/cli.js`:

```bash
node out/cli.js --help
```

## 2. Validate a Mod

The `validate` command checks a Factorio mod directory for structural
correctness — verifying required files, entry points, load order, and
info.json fields.

```bash
node out/cli.js validate /path/to/my-factorio-mod
```

A typical Factorio mod directory looks like:

```
my-mod/
├── info.json          # Mod metadata (name, version, dependencies)
├── data.lua           # Prototype definitions
├── control.lua        # Runtime event handlers
├── settings.lua       # Mod settings (optional)
└── locale/
    └── en/
        └── locale.cfg
```

**Example output** for a valid mod:

```
Validating: ./my-mod

  ✓ No issues found
```

**Example output** for a mod with issues:

```
Validating: ./my-mod

  ✗ Required file missing: info.json
    → Create info.json in the mod root directory
  ⚠ Mod has no data.lua or control.lua — it won't affect the game
    → Create data.lua (for prototypes) or control.lua (for runtime scripts)

  1 error(s), 1 warning(s), 0 info
```

Exit code is `0` when no errors are found, `1` when errors are present.

## 3. Parse Lua Files

The `parse` command parses a Lua source file and prints the AST as JSON.

### From a file

```bash
node out/cli.js parse data.lua
```

### From stdin

```bash
echo 'local x = 42' | node out/cli.js parse -
```

**Example output:**

```json
{
  "type": "Chunk",
  "body": [
    {
      "type": "LocalStatement",
      "variables": [
        { "type": "Identifier", "name": "x" }
      ],
      "init": [
        { "type": "NumericLiteral", "value": 42 }
      ]
    }
  ]
}
```

### Parsing invalid Lua

When given broken Lua, the tool prints an error message and exits with code 1:

```bash
echo 'funtion oops()' | node out/cli.js parse -
# Parse error: [1:8] '=' expected near 'oops'
```

## 4. Copy-Paste Lua Snippets

Use these snippets to test the tool yourself.

### Valid Lua — Factorio mod control script

```lua
-- control.lua: Register event handlers for a Factorio mod
script.on_event(defines.events.on_player_created, function(event)
  local player = game.get_player(event.player_index)
  if player then
    player.print("Welcome to the server!")
    player.insert({ name = "iron-plate", count = 50 })
  end
end)
```

### Valid Lua — Factorio data prototype

```lua
-- data.lua: Define a custom item and recipe
data:extend({
  {
    type = "item",
    name = "super-fuel",
    icon = "__my-mod__/graphics/icons/super-fuel.png",
    icon_size = 64,
    stack_size = 50,
    fuel_category = "chemical",
    fuel_value = "100MJ",
  },
})
```

### Invalid Lua — Missing `end` keyword

```lua
-- BROKEN: function body never closed
function setup_player(player)
  player.print("Setting up...")
  if player.force.name == "player" then
    player.insert({ name = "pistol", count = 1 })
  -- missing 'end' for the if block
-- missing 'end' for the function
```

### Invalid Lua — Typo in keyword

```lua
-- BROKEN: 'funtion' is not a keyword
funtion on_init()
  game.print("Hello")
end
```

## What to Expect

| Input | `parse` result |
|-------|----------------|
| Valid Lua | JSON AST, exit code 0 |
| Invalid Lua | Error message to stderr, exit code 1 |

| Input | `validate` result |
|-------|-------------------|
| Valid mod directory | "No issues found", exit code 0 |
| Mod with issues | Diagnostics with icons and suggestions, exit code 0 or 1 |
| Missing info.json | Error diagnostic, exit code 1 |

## Advanced: MCP Server Setup

For integration with MCP clients (e.g., Claude Desktop), you can run the
MCP server instead of the CLI:

```bash
npx shadow-cljs compile server
node out/server.js
```

The server communicates over stdio using JSON-RPC. Configure it in your
MCP client by adding to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "factorio-mod-tool": {
      "command": "node",
      "args": ["out/server.js"],
      "cwd": "/path/to/factorio-mod-tool"
    }
  }
}
```
