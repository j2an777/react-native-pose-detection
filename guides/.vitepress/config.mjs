// The docs site, built from guides/ and published by .github/workflows/docs-site.yml. It imports
// nothing from VitePress, so no dependency is added: `npx vitepress@1.6.4 dev guides`. Links that
// leave guides/ are rewritten to GitHub, so they work in both places.

import { readFileSync } from 'node:fs';
import path from 'node:path';

const REPOSITORY = 'https://github.com/khalid999devs/react-native-pose-detection';
const SITE = 'https://khalid999devs.github.io/react-native-pose-detection/';
const IMAGE = `https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/export-frame.png`;

const NAME = 'React Native Pose Detection';
const HOME_TITLE = 'Real-time pose detection for React Native and Expo';
const HOME_DESCRIPTION =
  'On-device pose detection for React Native and Expo: body tracking, joint angles and rep ' +
  'counting, powered by MediaPipe, with a native skeleton overlay.';

/** Pages whose first paragraph is a poor search result. Not frontmatter, which GitHub renders. */
const DESCRIPTIONS = {
  'getting-started.md':
    'Add real-time pose detection to a React Native or Expo app: install, grant the camera, and see a ' +
    'tracked skeleton, then read landmarks and count reps.',
  'installation.md':
    'Install react-native-pose-detection with Expo or bare React Native: the config plugin, the model, ' +
    'camera permissions, EAS and release builds.',
  'performance.md':
    'How the frame rate follows each phone: profiles, the duty-cycle governor, heat, idle and Low Power ' +
    'Mode, the model sizes, and what each costs.',
  'triggers.md':
    'Count reps and detect positions natively: declarative angle and position conditions evaluated on ' +
    'the camera thread, one event per repetition.',
  'recipes.md':
    'What you can build with pose detection in React Native: rep counters, form checks, holds and ' +
    'jumps, with the trigger for each and its honest limits.',
};

/** A link out of guides/, as a GitHub URL for the same file, or null when it stays inside. */
function outside(href, relativePath) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('#') || href.startsWith('/'))
    return null;
  const [target, anchor] = href.split('#');
  const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(relativePath), target));
  if (!resolved.startsWith('../')) return null;
  const file = path.posix.normalize(path.posix.join('guides', resolved));
  const kind = path.posix.extname(file) ? 'blob' : 'tree';
  return `${REPOSITORY}/${kind}/main/${file}${anchor ? `#${anchor}` : ''}`;
}

/** Rewrites links and images that leave guides/, by the source path VitePress passes in `env`. */
function repositoryLinks(md) {
  const rewrite = (token, attribute, env) => {
    const value = token.attrGet(attribute);
    // The only rewrite, README to index, stays in its folder, so relative links still resolve.
    const { relativePath } = env;
    if (!value || !relativePath) return;
    const url = outside(value, relativePath);
    if (!url) return;
    token.attrSet(
      attribute,
      attribute === 'src'
        ? url.replace(`${REPOSITORY}/blob/main/`, IMAGE.replace('ss/export-frame.png', ''))
        : url,
    );
  };
  md.core.ruler.after('inline', 'repository-links', (state) => {
    for (const block of state.tokens) {
      for (const token of block.children ?? []) {
        if (token.type === 'link_open') rewrite(token, 'href', state.env);
        if (token.type === 'image') rewrite(token, 'src', state.env);
      }
    }
  });
}

/** The first paragraph of prose after the title, as plain text: what a search result shows. */
function firstParagraph(source) {
  const body = source.replace(/^---[\s\S]*?---\n/, '');
  const lines = body.split('\n');
  const paragraph = [];
  let fenced = false;
  for (const line of lines) {
    if (line.startsWith('```')) {
      fenced = !fenced;
      continue;
    }
    if (fenced) continue;
    const text = line.trim();
    // Headings, tables, HTML, quotes, images and list items are not prose; text opening in bold is.
    const prose = text !== '' && !/^(#|\||<|>|!\[|[-*+] |\d+\. )/.test(text);
    if (prose) paragraph.push(text);
    else if (paragraph.length > 0) break;
  }
  const plain = paragraph
    .join(' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[`*_]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (plain.length <= 160) return plain;
  const cut = plain.slice(0, 157);
  return `${cut.slice(0, cut.lastIndexOf(' '))}…`;
}

/** The page's own URL on the site, without `.md` or a trailing `index`. */
function pageUrl(relativePath) {
  return SITE + relativePath.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '');
}

export default {
  title: NAME,
  titleTemplate: `:title · ${NAME}`,
  description: HOME_DESCRIPTION,
  lang: 'en-US',
  base: '/react-native-pose-detection/',
  cleanUrls: true,
  lastUpdated: true,
  // The guides index is the home page, so the site and the GitHub folder open on the same page.
  rewrites: { 'README.md': 'index.md' },
  sitemap: { hostname: SITE },

  head: [
    ['meta', { name: 'theme-color', content: '#0B1220' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: NAME }],
    ['meta', { property: 'og:image', content: IMAGE }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:image', content: IMAGE }],
  ],

  markdown: {
    config: (md) => repositoryLinks(md),
  },

  transformPageData(pageData, { siteConfig }) {
    if (pageData.relativePath === 'index.md') {
      pageData.title = HOME_TITLE;
      pageData.description = HOME_DESCRIPTION;
      return;
    }
    if (pageData.frontmatter.description) return;
    if (DESCRIPTIONS[pageData.relativePath]) {
      pageData.description = DESCRIPTIONS[pageData.relativePath];
      return;
    }
    const source = readFileSync(path.join(siteConfig.srcDir, pageData.filePath), 'utf8');
    const description = firstParagraph(source);
    if (description) pageData.description = description;
  },

  transformHead({ pageData, title, description }) {
    const url = pageUrl(pageData.relativePath);
    return [
      ['link', { rel: 'canonical', href: url }],
      ['meta', { property: 'og:url', content: url }],
      ['meta', { property: 'og:title', content: title }],
      ['meta', { property: 'og:description', content: description }],
      ['meta', { name: 'twitter:title', content: title }],
      ['meta', { name: 'twitter:description', content: description }],
    ];
  },

  themeConfig: {
    siteTitle: NAME,
    search: { provider: 'local' },
    nav: [
      { text: 'Get started', link: '/getting-started' },
      { text: 'Reference', link: '/reference/pose-camera' },
      { text: 'npm', link: 'https://www.npmjs.com/package/react-native-pose-detection' },
    ],
    sidebar: [
      {
        text: 'Start here',
        items: [
          { text: 'Getting started', link: '/getting-started' },
          { text: 'Installation', link: '/installation' },
          { text: 'Camera control', link: '/camera-control' },
          { text: 'Data delivery', link: '/data-delivery' },
          { text: 'Triggers', link: '/triggers' },
        ],
      },
      {
        text: 'Going further',
        items: [
          { text: 'Performance', link: '/performance' },
          { text: 'Photos and video files', link: '/files' },
          { text: 'What you can build', link: '/recipes' },
          { text: 'Troubleshooting', link: '/troubleshooting' },
        ],
      },
      {
        text: 'API reference',
        items: [
          { text: '<PoseCamera> props', link: '/reference/pose-camera' },
          { text: 'Ref methods', link: '/reference/ref-methods' },
          { text: 'Events', link: '/reference/events' },
          { text: 'Functions', link: '/reference/functions' },
          { text: 'Types', link: '/reference/types' },
          { text: 'Camera permission', link: '/reference/permissions' },
          { text: 'Trigger schema', link: '/reference/trigger-schema' },
          { text: 'Config plugin', link: '/reference/config-plugin' },
          { text: 'CLI', link: '/reference/cli' },
        ],
      },
    ],
    socialLinks: [{ icon: 'github', link: REPOSITORY }],
    editLink: {
      pattern: `${REPOSITORY}/edit/main/guides/:path`,
      text: 'Edit this page on GitHub',
    },
    footer: {
      message: 'MIT licensed. Built from the guides in the repository.',
    },
  },
};
