# SQL Assist for DBeaver

A DBeaver plugin that makes long `INSERT` statements easy to read, edit and check, inspired by
the INSERT editing features of JetBrains DataGrip / IntelliJ.

| Feature | What it does |
|---|---|
| **Column ↔ value highlighting** | Put the cursor on a column name and its value in every `VALUES` row lights up, and the other way round. |
| **Vertical INSERT editor** | Shows an INSERT as a `Column \| Row 1 \| Row 2 …` grid and writes your edits back to the script. |
| **Live type and constraint checks** | Underlines values that don't fit the connected table: wrong types, `NULL` in `NOT NULL` columns, too-long strings, unknown columns. |
| **Inline column hints** | Faint `name (varchar(50)):` labels in front of each value, so you never count commas again. |
| **JSON / XML / long text editor** | A `✎ JSON` link after long values opens them formatted, with validation, and writes them back correctly quoted. |

Tested with **DBeaver 26.2.3** (Community, Java 21) on macOS.

## Installation

DBeaver ships with the Eclipse dropins scanner disabled, so the jar can't simply be dropped into
`dropins/`. The install script copies it to DBeaver's `plugins/` folder and registers it in
`configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`, keeping a backup of the original
file as `bundles.info.sqlassist-backup`.

**A DBeaver update replaces `bundles.info`**, which removes the plugin: run the install again after
updating DBeaver.

### From a release (recommended)

1. Download `com.sqlassist.dbeaver_<version>.jar` and `install.sh` from the
   [latest release](https://github.com/tnusraddinov/dbeaver-sql-assist/releases/latest).
2. Quit DBeaver.
3. Run:

   ```bash
   bash install.sh com.sqlassist.dbeaver_<version>.jar
   ```

4. Start DBeaver.

The script finds DBeaver in the default locations on macOS (`/Applications/DBeaver.app`), Linux
(`/usr/share/dbeaver-ce`, `/opt/dbeaver`) and Windows with Git Bash (`C:\Program Files\DBeaver`).
Otherwise set `DBEAVER_HOME` to the folder that contains `plugins/` and `configuration/`:

```bash
DBEAVER_HOME=/path/to/dbeaver bash install.sh com.sqlassist.dbeaver_<version>.jar
```

If DBeaver is installed system-wide, the script may need `sudo`. Snap and Flatpak installations are
read-only and not supported.

To remove the plugin: `bash install.sh --uninstall`, then restart DBeaver.

**Manual install (any OS):** copy the jar into DBeaver's `plugins/` folder and add this line to the
end of `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`, using the jar's version:

```text
com.sqlassist.dbeaver,<version>,plugins/com.sqlassist.dbeaver_<version>.jar,4,true
```

### From source

You need a JDK 21+ and a local DBeaver installation to compile against; Maven, Tycho and Eclipse are
not required.

```bash
git clone https://github.com/tnusraddinov/dbeaver-sql-assist.git
cd dbeaver-sql-assist
./build.sh install
```

| Command | Effect |
|---|---|
| `./build.sh` | Compiles, runs the tests, writes the plugin jar to `dist/` |
| `./build.sh install` | Same, then installs the jar into DBeaver |
| `./build.sh uninstall` | Removes the plugin from DBeaver |

`build.sh` compiles against `/Applications/DBeaver.app/Contents/Eclipse` by default; set
`DBEAVER_HOME` for other locations. On Windows, run it from Git Bash or WSL.

## Usage

All shortcuts work inside the SQL editor. On macOS, Alt is the Option key. Every command is also in the
editor's right-click menu.

| Shortcut | Command |
|---|---|
| Shift+Alt+J | Select Column/Value Counterpart |
| Shift+Alt+V | Edit INSERT Vertically… |
| Shift+Alt+E | Edit String Value… |
| (menu only) | Toggle INSERT Column Hints |

Shortcuts can be changed in DBeaver's preferences (*Window → Preferences*, on macOS *DBeaver → Settings*)
under *User Interface → Keys*, category "SQL Assist".

### 1. Column ↔ value highlighting

```sql
INSERT INTO users (id, name, age) VALUES (1, 'somename', 25), (2, 'x', 30);
```

- Cursor on `name`: `'somename'` and `'x'` are highlighted.
- Cursor on `30`: `age` is highlighted.
- **Shift+Alt+J** jumps to and selects the counterpart. Highlighting alone never moves the cursor.

Works with multi-row `VALUES`, `INSERT … SELECT`, nested function calls, strings that contain commas
or parentheses, and comments. Dialect forms such as MySQL `VALUES ROW(…)` / `INSERT IGNORE` /
`REPLACE INTO`, SQLite `INSERT OR REPLACE`, T-SQL `INSERT t (…)` / `WITH (TABLOCK)` and PostgreSQL
`OVERRIDING SYSTEM VALUE`, `$1`, `:name`, `::cast`, `E'…'` and `$$…$$` are understood.

### 2. Vertical INSERT editor

**Shift+Alt+V** with the cursor inside an INSERT opens a grid: one line per column, one grid column per
`VALUES` row (`INSERT … SELECT` shows the SELECT expressions).

- Edit with double-click, Enter or F2; Tab moves between cells. Column names are editable too.
- Values are raw SQL: keep the quotes on strings, or type `NULL`, `now()` and so on.
- Changed cells are bold; hover shows the old value. The filter box searches names and values.
- With more than 8 rows, the *First row* control pages through them.
- **Apply to Editor** replaces only the changed parts of the statement. Comments, indentation and line
  breaks stay as they were, and one undo reverts everything.
- Multi-line values open in the value editor (feature 5) on double-click or via *Open in Value Editor…*.

DBeaver already uses Ctrl+Shift+V for *Paste Special*, which is why this command uses Shift+Alt+V.

### 3. Live type and constraint checks

When the SQL editor is connected to a database, each INSERT in the script is checked against the
table's metadata about 0.7 seconds after you stop typing. Errors get a red wavy underline, warnings a
yellow one; hover to read the message.

| Problem | Level |
|---|---|
| Column not in the table, or listed twice | Error |
| Row has more or fewer values than columns | Error |
| `NULL` into a `NOT NULL` column (identity / auto-increment excepted) | Error |
| Text that isn't a number (`'abc'`) into an INTEGER / NUMERIC column | Error |
| String longer than the column's `VARCHAR(n)` / `CHAR(n)` limit | Error |
| Invalid value for a BOOLEAN column | Error |
| `NOT NULL` column without a default left out of the column list | Warning (on the table name) |
| Number written as text (`'25'`), or `2.5` into an INTEGER column | Warning |
| Text that doesn't look like a date in a DATE / TIME column | Warning |

To avoid false alarms, only plain values are judged. Expressions such as `now()`, `upper(x)`,
`'5'::int`, `$1` or subqueries are never flagged, and an INSERT into a table that can't be found (for
example one created later in the same script) is skipped. `INSERT INTO t VALUES (…)` without a column
list is checked against the table's columns in order.

Checks re-run when you connect, disconnect or switch connection/schema. Table metadata is cached for
30 seconds, so a table you just altered may take that long to be picked up.

### 4. Inline column hints

```text
INSERT INTO users (id, name) VALUES (id (int4): 1, name (varchar(50)): 'somename')
```

The labels are drawn on screen only; they are never part of the script.

- Names come from the column list or, if there is none, from the connected table.
- Types are shown only while the editor is connected.
- No hints for single-column INSERTs, for values beyond the column count, or for values that already
  name their column (`:id`, `@name`, `u.id`).
- Turn them on or off with right-click → *Toggle INSERT Column Hints*. The setting is remembered.

### 5. JSON / XML / long text editor

A clickable `✎ JSON`, `✎ XML` or `✎ 240 chars` link appears after a string value when it is JSON or
XML (30+ characters or multi-line) or long text (80+ characters or multi-line). Click it, or put the
cursor on any string value and press **Shift+Alt+E**.

- The value opens without SQL quoting; JSON and XML are pretty-printed.
- *Format*, *Minify* (JSON) and *Wrap lines* are available, and the content is validated as you type,
  with the error's line and column.
- **Apply to Script** writes the value back with the same quoting it had (`'` doubled inside `'…'`,
  backslash escapes in `E'…'`, a fresh tag for `$$…$$` if needed).
- If the value was on one line, *Write back as a single line* is preselected, so JSON is written back
  compact and the INSERT keeps one row per line.
- Applying without changes leaves the script untouched; one undo reverts an applied change.
- The XML parser refuses DTDs and external entities (no XXE).

### Appearance

Colors and underline styles are in the preferences under *Editors → Text Editors → Annotations*:
"SQL Assist: INSERT column/value pair", "SQL Assist: INSERT validation error" and
"SQL Assist: INSERT validation warning".

## Limitations

- Only top-level SQL editors are supported, not SQL tabs embedded in object editors.
- Column ↔ value highlighting needs a column list (`INSERT INTO t (a, b) …`).
- Not supported in the value editor: expressions like `'{}'::jsonb` or `'a' || 'b'`, and hex/bit strings
  (`X'…'`, `B'…'`). MySQL strings that use backslash escapes are shown with the backslashes as written.
- Scripts over 1 MB are analysed only around the cursor; inline hints and links in scripts over 300 KB
  only around the visible area.
- The plugin uses DBeaver editor classes that are not a public API, so a future DBeaver version may
  need adjustments.

## Development

Architecture, project layout and the reasoning behind non-obvious choices are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). In short:

- The SQL analysis (`parser`, `vertical`, `validation`, `hints`, `values` packages) is plain Java with
  no Eclipse or DBeaver dependency, and is covered by dependency-free tests that `build.sh` runs.
- The `ui` packages connect it to DBeaver's SQL editor.

To work in Eclipse PDE: set the target platform to your DBeaver installation folder, import the project
with *Existing Projects into Workspace* and run it as an *Eclipse Application*.

Issues and pull requests are welcome.

## License

[MIT](LICENSE)

This is an independent project. It is not affiliated with or endorsed by DBeaver Corp or JetBrains.
