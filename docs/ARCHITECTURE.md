# Architecture

SQL Assist is a single OSGi bundle (`com.sqlassist.dbeaver`) that plugs into DBeaver's SQL editor
(`org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase`). The code is split into two layers:

- **Analysis** (`parser`, `vertical`, `validation`, `hints`, `values`): plain Java 21 with no Eclipse
  or DBeaver imports. It works on the script text and returns positions (`Span`s) and results.
  Everything here is unit-tested.
- **Integration** (`ui.*`): attaches to the editor, collects input (text, caret, connection,
  metadata), calls the analysis layer and renders results as annotations, code minings and dialogs.

## Project layout

```
META-INF/MANIFEST.MF          OSGi bundle definition (requires DBeaver SQL editor + JFace Text)
plugin.xml                    startup hook, annotation types, code-mining providers, commands, keys, menus
build.properties              PDE build metadata
build.sh                      dependency-free build, tests and packaging; install / uninstall
scripts/install.sh            standalone installer, also shipped with each release
src/com/sqlassist/dbeaver/
  parser/                     tokenizer and INSERT parser (used by every feature)
    SqlTokenizer              lexer aware of strings, quoted identifiers, comments, $$...$$ blocks
    InsertStatementParser     finds INSERTs; splits column list and VALUES / SELECT items
    InsertStatement           column and row spans; crossReferenceAt(offset)
    CrossReference, Span, SqlToken
  vertical/                   feature 2 model
    InsertGrid                editable transposed INSERT; turns edits into TextReplacements
    TextReplacement
  validation/                 feature 3 rules
    InsertValidator           checks an INSERT against TableMeta, returns Problems
    Literal                   classifies a value as NULL / STRING / NUMBER / expression
    TableMeta, ColumnMeta, Problem
  hints/                      feature 4
    ColumnHints               computes the "name (type): " labels
  format/                     SQL formatter
    SqlFormatter              DataGrip-style layout engine + token-by-token safety check
    Keywords                  words whose case may change (reserved in every major database)
  values/                     feature 5
    StringLiteral             'x' / N'x' / E'x' / $$x$$ <-> plain content, same quoting on write-back
    JsonFormatter             dependency-free JSON validation, pretty-printing and minifying
    XmlFormatter              JDK-based XML pretty-printing with DTDs / external entities disabled
    LongValues, ValueKind     finds long / JSON / XML string values
  ui/
    SqlAssistStartup          IStartup: attaches the per-editor add-ons to every SQL editor
    highlight/                feature 1: CrossHighlighter, InsertModelCache
    vertical/                 feature 2: VerticalInsertDialog
    validation/               feature 3: LiveValidator, TableMetadataResolver, ValidationAnnotation
    hints/                    feature 4: ColumnHintProvider, ColumnHintMining, ColumnHintUpdater, HintPreferences
    values/                   feature 5: ValueEditorDialog, ValueEditorProvider, ValueEditing
    format/                   formatter: DataGripStyleFormatter (DBeaver sqlFormatter extension), options
    handlers/                 command handlers for the menu entries and shortcuts
test/                         dependency-free tests (each *Test class has a main method; build.sh runs all)
```

## Parsing

`SqlTokenizer` skips comments and turns each string, quoted identifier (`"x"`, `` `x` ``, `[x]`) and
dollar-quoted block into a single token, so commas and parentheses inside them are never mistaken for
separators. It never throws: an unterminated string simply runs to the end of the text, which keeps
the features working while the user is typing.

`InsertStatementParser` finds `INSERT` / `REPLACE` / `UPSERT` keywords at parenthesis depth 0, reads
the table name, then splits the column list and each `VALUES` tuple (or the `SELECT` list) **only at
commas of depth 1**. `coalesce(x, 1)`, `ARRAY[1,2]` and subqueries therefore stay single items. Empty
items such as `(1, , 3)` become zero-length spans so indices stay aligned with the column list.

Documents over 1 MB are tokenized only in a ±200 KB window around the caret.

## Feature notes

### 1. Cross-highlighting

`CrossHighlighter` listens to JFace post-selection events (already debounced by JFace) and, with a
250 ms delay, to text changes. `InsertModelCache` re-parses only when the document's modification
stamp changes, so plain caret moves cost a binary search. Highlights are annotations of type
`com.sqlassist.dbeaver.crossHighlight`, replaced in one `IAnnotationModelExtension.replaceAnnotations`
call. Installation is idempotent: a data key on the `StyledText` marks an editor as handled, and a
dispose listener removes everything with the widget.

### 2. Vertical editor

`InsertGrid` holds the raw SQL text of every column name and value. Only modified slots produce
`TextReplacement`s; they are applied as one `MultiTextEdit` inside an `IRewriteTarget` compound
change, so formatting and comments survive and one undo reverts the whole edit. If the document
changed while the modal dialog was open, nothing is applied.

### 3. Validation

`LiveValidator` waits for a 700 ms typing pause, snapshots the text, then parses and validates in a
background `Job`. Results are applied on the UI thread only if the document's modification stamp is
unchanged. The editor's execution context is polled every 3 seconds so connecting, disconnecting or
switching connection / schema triggers a re-check.

`TableMetadataResolver` resolves table names with `SQLSearchUtils.findObjectByFQN` (the lookup
DBeaver's completion uses, honouring default catalog / schema, quoting and identifier case) and reads
columns via `DBSEntity.getAttributes`. One shared cache, keyed by execution context and table name,
serves validation and column hints; entries (including "not found") live for 30 seconds.

DBeaver's SQL editor hover only shows text for its own problem annotations and for
`SpellingAnnotation`s. `ValidationAnnotation` therefore extends `SpellingAnnotation` but sets its own
annotation type (a subtype of the standard error / warning types), so the spell checker never removes
it and DBeaver's hover still shows the message.

### 4. Column hints

The hints are JFace code minings (`LineContentCodeMining`), registered through the
`org.eclipse.ui.workbench.texteditor.codeMiningProviders` extension point with an `enabledWhen`
expression limited to `SQLEditorBase`. DBeaver's editor (via `AbstractTextEditor`) installs registered
providers itself. Eclipse does not recompute minings on its own, so `ColumnHintUpdater` calls
`updateCodeMinings()` after a 500 ms typing pause, when the connection changes and, in large scripts,
after scrolling (only the visible area ±20 KB is mined above 300 KB).

### 5. Value editor

`ValueEditorProvider` is a second code-mining provider that draws a clickable link just before the
character that follows a long string value. The mining's position is tracked by the document, so it
still points at the value's end when clicked. `StringLiteral` separates quoting from content and
re-quotes edited content in the original style. `JsonFormatter` copies strings and numbers verbatim,
so formatting never changes values or precision.

### 6. Formatter

`SqlFormatter` works on the token stream including comments. The script is cut into units: a
statement ends at a depth-0 `;` (plus comments on the same line), before a blank line, or before a
DBeaver command line (`@set`, `@include`, …, `GO`, `DELIMITER`). Units whose first word is not a
supported statement, or whose parentheses are unbalanced, are copied verbatim.

Supported statements are laid out by a small state machine: clause keywords (including multi-word ones
such as `GROUP BY`, `LEFT OUTER JOIN`, `ON CONFLICT`, `UNION ALL`) start a line at the statement's
indent; commas in SELECT / SET / VALUES lists break and align to the first item; AND/OR at depth 0 in
WHERE / HAVING break and align under the clause keyword (`BETWEEN … AND` excepted); parentheses that
contain a query become indented blocks, other parentheses stay inline; `CASE` puts WHEN / ELSE / END
on their own lines.

Lines are wrapped at a right margin (`Options.lineWidth`, 120 by default, 0 = off). Before each
item of a comma list (inside parentheses, or GROUP BY / ORDER BY / FROM / RETURNING at clause level)
the item's one-line width is measured; if it would cross the margin, the line breaks after the comma
and continues at the column of the list's first item. When that column is beyond two thirds of the
margin, the continuation falls back to the line's indent plus two indent steps. A subquery counts only
up to its `(`, because it becomes its own block. Wrapping only ever adds line breaks after commas, so
the safety rules below are unaffected.

Two rules keep it safe:

1. **Spacing never joins or splits tokens.** Two tokens that touched in the input still touch; a space
   is only added after a comma and only removed next to `( ) , ; .`. So `::`, `>=` or `->>`, which the
   tokenizer sees as separate characters, cannot be torn apart.
2. **Verification.** The output is tokenized again and compared with the input: same number of tokens,
   same types, same text except the case of words in `Keywords`. Any difference throws
   `SqlFormatException` and the editor is left unchanged.

`Result.mapOffset` maps the caret through the per-token output offsets, and `FormatSqlHandler`
replaces only the differing middle part of the text so markers elsewhere stay put.
`DataGripStyleFormatter` exposes the same engine through DBeaver's `org.jkiss.dbeaver.sqlFormatter`
extension point; on a failed check it returns the input unchanged.

## Installing without dropins

DBeaver ships the Eclipse p2 dropins reconciler disabled (`bundles.info` marks it as not started), so
`dropins/` is ignored. `scripts/install.sh` (also used by `build.sh install`) copies the jar to
`plugins/` and appends a line to
`configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`. Equinox's simple configurator
installs it at the next start.

## Testing

`./build.sh` compiles everything against the jars of the local DBeaver installation and runs every
`*Test` class under `test/`. The tests have no dependencies (no JUnit), so they run without a network
or a Maven repository. UI behaviour has to be checked in DBeaver itself; the checklist below covers
each feature.

1. `./build.sh install`, then restart DBeaver.
2. In a new SQL script type
   `INSERT INTO users (id, name, age) VALUES (1, 'somename', 25), (2, 'x', 30);`
3. Cursor on `name`: `'somename'` and `'x'` are highlighted. Cursor on `30`: `age` is highlighted.
4. Shift+Alt+J selects the counterpart.
5. Shift+Alt+V opens the grid. Change *Row 1* of `name` to `'new'` and apply: only `'somename'`
   changes in the script, and one undo reverts it.
6. Connected to a real database, give an INTEGER column `'abc'`, a NOT NULL column `NULL` and a
   VARCHAR(n) column a longer string: red underlines appear within about a second, with hover text.
7. Faint `column:` labels (`column (type):` when connected) appear before each value; right-click →
   *Toggle INSERT Column Hints* hides and shows them.
8. `INSERT INTO t (id, data) VALUES (1, '{"a": 1, "b": [1, 2, 3], "c": "a longer value"}');` shows a
   `✎ JSON` link. It opens formatted JSON; after an edit, *Apply to Script* writes it back on one line
   with correct quoting.
9. With nothing selected, Cmd+Option+L / Ctrl+Alt+L formats the whole script; with a selection,
   only the selected lines. One undo reverts it.
