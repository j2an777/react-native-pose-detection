const path = require('node:path');

const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// The package is a workspace symlink: watch the repo root and resolve its node_modules too.
const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

module.exports = mergeConfig(getDefaultConfig(projectRoot), {
  watchFolders: [workspaceRoot],
  resolver: {
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(workspaceRoot, 'node_modules'),
    ],
    disableHierarchicalLookup: true,
  },
});
