import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/build/**',
      '**/lib/**',
      '.test-build/**',
      '**/node_modules/**',
      '**/android/**',
      '**/ios/**',
      // The documentation site VitePress builds from guides/.
      'guides/.vitepress/dist/**',
      'guides/.vitepress/cache/**',
    ],
  },
  ...tseslint.configs.recommended,
  {
    // CommonJS because Expo, Node, Metro and Babel load these through require, no bundler between.
    files: [
      'packages/core/app.plugin.js',
      'packages/core/cli/index.js',
      'example/*/metro.config.js',
      'example/*/babel.config.js',
    ],
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
  {
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-explicit-any': 'error',
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },
);
