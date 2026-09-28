import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { test } from 'node:test';
import ts from 'typescript';

import { ERROR_CODES } from '../../src/types/events';

/**
 * Nothing at runtime compares the reference pages to the types. Members are read through the
 * compiler, not a regex, so one from an intersection or a mapped type still counts.
 */
const CORE = resolve(__dirname, '../../..', 'packages/core');
const REFERENCE = resolve(CORE, '../../guides/reference');

function membersOf(typeName: string): string[] {
  const entry = resolve(CORE, 'src/types/props.ts');
  const program = ts.createProgram([entry], {
    target: ts.ScriptTarget.ES2022,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    strict: true,
    noEmit: true,
  });
  const checker = program.getTypeChecker();
  const source = program.getSourceFile(entry);
  assert.ok(source !== undefined, `${entry} is not in the program`);

  let found: ts.Type | null = null;
  ts.forEachChild(source, (node) => {
    if (ts.isTypeAliasDeclaration(node) && node.name.text === typeName) {
      found = checker.getTypeAtLocation(node.name);
    }
  });

  assert.ok(found !== null, `${typeName} is not declared in ${entry}`);
  return checker.getPropertiesOfType(found).map((symbol) => symbol.getName());
}

/** Every reference page as one string: which page documents a member does not matter here. */
function reference(): string {
  return readdirSync(REFERENCE)
    .filter((name) => name.endsWith('.md'))
    .map((name) => readFileSync(resolve(REFERENCE, name), 'utf8'))
    .join('\n');
}

function documents(docs: string, name: string): boolean {
  // Backticked on its own, or declared in a code fence with an optional marker or a call.
  return (
    docs.includes(`\`${name}\``) ||
    new RegExp(`^\\s*${name}\\??[:(]`, 'm').test(docs) ||
    docs.includes(`\`${name}(`)
  );
}

test('every <PoseCamera> prop is documented in the reference', () => {
  const docs = reference();
  const undocumented = membersOf('PoseCameraProps').filter((name) => !documents(docs, name));

  assert.deepEqual(
    undocumented,
    [],
    'these props exist on PoseCameraProps but appear nowhere in guides/reference/',
  );
});

test('every ref method is documented in the reference', () => {
  const docs = reference();
  const undocumented = membersOf('PoseCameraRef').filter((name) => !documents(docs, name));

  assert.deepEqual(undocumented, [], 'these methods exist on PoseCameraRef but are not documented');
});

test('the events table lists exactly the callbacks the props declare', () => {
  const source = readFileSync(resolve(REFERENCE, 'events.md'), 'utf8');
  const table = /\| Callback \| Fires \| Rate \|\n\|[^\n]*\|\n([\s\S]*?)\n\n/.exec(source)?.[1];
  assert.ok(table !== undefined, 'could not find the callback table in events.md');

  // A row names its callback in backticks, and may link it to the callback's own section.
  const documented = [...table.matchAll(/^\| \[?`(on\w+)`(?:\]\([^)]*\))? \|/gm)]
    .map((match) => match[1])
    .sort();
  const declared = membersOf('PoseCameraProps')
    .filter((name) => name.startsWith('on'))
    .sort();

  assert.deepEqual(documented, declared);
});

test('every error code has a row in the events reference', () => {
  const source = readFileSync(resolve(REFERENCE, 'events.md'), 'utf8');
  const documented = [...source.matchAll(/^\| `([A-Z_]+)` \|/gm)].map((match) => match[1]);
  const missing = ERROR_CODES.filter((code) => !documented.includes(code));

  assert.deepEqual(missing, [], 'these ErrorCodes are in the union but have no documented row');

  // Nor a documented code the union dropped, which a consumer would switch on in vain.
  const stale = documented.filter((code) => !ERROR_CODES.includes(code as never));
  assert.deepEqual(stale, []);
});

/** What `src/index.ts` exports: values (functions, classes, constants), or else only types. */
function exportedNames(kind: 'values' | 'types'): string[] {
  const entry = resolve(CORE, 'src/index.ts');
  const program = ts.createProgram([entry], {
    target: ts.ScriptTarget.ES2022,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    jsx: ts.JsxEmit.ReactJSX,
    strict: true,
    noEmit: true,
  });
  const checker = program.getTypeChecker();
  const source = program.getSourceFile(entry);
  assert.ok(source !== undefined, `${entry} is not in the program`);
  const module = checker.getSymbolAtLocation(source);
  assert.ok(module !== undefined, `${entry} has no module symbol`);

  return checker
    .getExportsOfModule(module)
    .filter((symbol) => {
      const target =
        symbol.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(symbol) : symbol;
      return ((target.flags & ts.SymbolFlags.Value) !== 0) === (kind === 'values');
    })
    .map((symbol) => symbol.getName())
    .sort();
}

test('every function, class and constant the package exports is documented in the reference', () => {
  const docs = reference();
  const undocumented = exportedNames('values').filter(
    (name) => !documents(docs, name) && !docs.includes(`<${name}`),
  );

  assert.deepEqual(
    undocumented,
    [],
    'these are exported from src/index.ts but appear nowhere in guides/reference/',
  );
});

test('every type the package exports is named in the reference', () => {
  const docs = reference();
  const unnamed = exportedNames('types').filter((name) => !documents(docs, name));

  assert.deepEqual(
    unnamed,
    [],
    'these types are exported from src/index.ts but named nowhere in guides/reference/',
  );
});
