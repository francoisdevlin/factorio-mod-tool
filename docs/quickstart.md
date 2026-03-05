# Quickstart: Factorio Mod Tool

This guide walks you through building, starting, and using the Factorio Mod Tool
MCP server. By the end, you'll have validated a mod directory and parsed both
valid and invalid Lua — confirming the tool works correctly.

## Prerequisites

- [Node.js](https://nodejs.org/) (18+)
- npm (comes with Node.js)

Verify your setup:

```bash
node --version
npm --version
```

## 1. Build and Start the MCP Server

Clone the repository and start the server:

```bash
git clone https://github.com/francoisdevlin/factorio-mod-tool.git
cd factorio-mod-tool
```

Install dependencies and compile:

```bash
npm install
npx shadow-cljs compile server
```

Start the MCP server (communicates over stdio using JSON-RPC):

```bash
node out/server.js
```

The server is now listening on stdin for MCP tool-call requests. You can also
configure it in your MCP client (e.g., Claude Desktop) by adding to your
`claude_desktop_config.json`:

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

## 2. Using `validate-mod`

The `validate-mod` tool checks a Factorio mod directory for structural
correctness — verifying required files exist and Lua sources parse without
errors.

### Example: Validate a mod directory

Point it at any Factorio mod directory (one containing `info.json`):

```
validate-mod({ "path": "/path/to/my-factorio-mod" })
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

**Expected output** for a valid mod:

```
✓ info.json found and valid
✓ data.lua — parsed successfully
✓ control.lua — parsed successfully
2 Lua files checked, 0 errors
```

**Expected output** for a mod with issues:

```
✓ info.json found and valid
✓ data.lua — parsed successfully
✗ control.lua — parse error at line 12: unexpected symbol near '}'
2 Lua files checked, 1 error
```

## 3. Using `parse-lua` with Valid Lua

The `parse-lua` tool parses a Lua source string and returns the abstract syntax
tree (AST). This is useful for inspecting how the tool interprets Lua code.

### Example: Simple assignment

```
parse-lua({ "source": "local x = 42" })
```

**Expected output:**

```json
{
  "status": "ok",
  "ast": {
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
}
```

### Example: Function definition (common in Factorio mods)

```
parse-lua({ "source": "function on_tick(event)\n  local player = game.players[1]\n  player.print('Hello')\nend" })
```

**Expected output:**

```json
{
  "status": "ok",
  "ast": {
    "type": "Chunk",
    "body": [
      {
        "type": "FunctionDeclaration",
        "identifier": { "type": "Identifier", "name": "on_tick" },
        "parameters": [
          { "type": "Identifier", "name": "event" }
        ],
        "body": [
          {
            "type": "LocalStatement",
            "variables": [
              { "type": "Identifier", "name": "player" }
            ],
            "init": [
              {
                "type": "IndexExpression",
                "base": {
                  "type": "MemberExpression",
                  "base": { "type": "Identifier", "name": "game" },
                  "identifier": { "type": "Identifier", "name": "players" }
                },
                "index": { "type": "NumericLiteral", "value": 1 }
              }
            ]
          },
          {
            "type": "CallStatement",
            "expression": {
              "type": "CallExpression",
              "base": {
                "type": "MemberExpression",
                "base": { "type": "Identifier", "name": "player" },
                "identifier": { "type": "Identifier", "name": "print" }
              },
              "arguments": [
                { "type": "StringLiteral", "value": "Hello" }
              ]
            }
          }
        ]
      }
    ]
  }
}
```

### Example: Table constructor (Factorio prototype data)

```
parse-lua({ "source": "data:extend({\n  {\n    type = 'item',\n    name = 'my-item',\n    stack_size = 100\n  }\n})" })
```

This is the most common pattern in Factorio `data.lua` files — extending the
prototype table with new definitions.

## 4. Using `parse-lua` with Invalid Lua

When given broken Lua, `parse-lua` returns a structured error indicating what
went wrong and where.

### Example: Missing `end` keyword

```
parse-lua({ "source": "function foo()\n  print('hello')" })
```

**Expected output:**

```json
{
  "status": "error",
  "error": {
    "message": "'end' expected near <eof>",
    "line": 2,
    "column": 17
  }
}
```

### Example: Unmatched parenthesis

```
parse-lua({ "source": "print('hello'" })
```

**Expected output:**

```json
{
  "status": "error",
  "error": {
    "message": "')' expected near <eof>",
    "line": 1,
    "column": 14
  }
}
```

### Example: Invalid assignment target

```
parse-lua({ "source": "123 = 'bad'" })
```

**Expected output:**

```json
{
  "status": "error",
  "error": {
    "message": "unexpected symbol near '123'",
    "line": 1,
    "column": 1
  }
}
```

### Example: Unclosed string literal

```
parse-lua({ "source": "local msg = 'hello" })
```

**Expected output:**

```json
{
  "status": "error",
  "error": {
    "message": "unfinished string near ''hello'",
    "line": 1,
    "column": 13
  }
}
```

## 5. Copy-Paste Lua Snippets

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

script.on_event(defines.events.on_tick, function(event)
  if event.tick % 600 == 0 then
    for _, player in pairs(game.connected_players) do
      player.print("10 seconds have passed")
    end
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
    subgroup = "raw-material",
    order = "z[super-fuel]",
    stack_size = 50,
    fuel_category = "chemical",
    fuel_value = "100MJ",
  },
  {
    type = "recipe",
    name = "super-fuel",
    energy_required = 10,
    ingredients = {
      { type = "item", name = "solid-fuel", amount = 5 },
      { type = "item", name = "uranium-235", amount = 1 },
    },
    results = {
      { type = "item", name = "super-fuel", amount = 1 },
    },
  },
})
```

### Invalid Lua — Missing `end` (common mistake)

```lua
-- BROKEN: function body never closed
function setup_player(player)
  player.print("Setting up...")
  if player.force.name == "player" then
    player.insert({ name = "pistol", count = 1 })
  -- missing 'end' for the if block
-- missing 'end' for the function
```

### Invalid Lua — Mismatched brackets

```lua
-- BROKEN: table constructor has mismatched braces
data:extend({
  {
    type = "item",
    name = "broken-item",
    stack_size = 100
  -- missing closing '}' for inner table
})
```

### Invalid Lua — Typo in keyword

```lua
-- BROKEN: 'funtion' is not a keyword
funtion on_init()
  game.print("Hello")
end
```

### Invalid Lua — Stray operator

```lua
-- BROKEN: unexpected '==' in assignment context
local x == 42
```

## What to Expect

| Input | `parse-lua` result |
|-------|-------------------|
| Valid Lua | `status: "ok"` with full AST |
| Invalid Lua | `status: "error"` with line/column and message |

| Input | `validate-mod` result |
|-------|----------------------|
| Valid mod directory | All files pass, 0 errors |
| Mod with broken Lua | Error report with file, line, and message |
| Missing `info.json` | Structural error (not a valid mod) |

The tool gives you fast, reliable feedback on whether your mod's Lua is
syntactically correct — catching errors before you load the mod in Factorio.
