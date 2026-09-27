#!/usr/bin/env node
// The implementation lives in plugin/build, shared with the config plugin.
const { run } = require('../plugin/build/cli');

run(process.argv.slice(2))
  .then((code) => {
    process.exitCode = code;
  })
  .catch((error) => {
    process.stderr.write(`\n${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
