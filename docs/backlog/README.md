# Backlog

The work planned for the next releases, written so that anyone can pick an item up. Each item
says what to change, where, and how it is checked. Work that is merged and not yet released is under
`## Unreleased` in [the changelog](../../packages/core/CHANGELOG.md).

## Files

| File | What is in it | Ships in |
| --- | --- | --- |
| [next-patch.md](./next-patch.md) | Small fixes that change no native binary | the next patch, or any time |
| [native.md](./native.md) | Native upgrades, and changes only a phone can prove | a release of their own, after a device run |
| [features.md](./features.md) | New capabilities and new API | 0.3.0 and later |
| [waiting.md](./waiting.md) | Items blocked on Expo, React Native, GitHub or another project | when that project moves |

**Good first pull requests:** CI-1, CI-4, EX-8 and EX-10, all in [next-patch.md](./next-patch.md).

## Picking an item

- **Claim it first.** Open an issue or a draft pull request with the item's ID, such as `PKG-1`,
  so that two people don't do the same work. Put the ID in the pull request's description too.
- **Follow the contributor rules.** Branches, commits, and what must pass are in
  [contributing](../contributing.md). Run `npm run check` before you push.
- **Native changes need a device.** Changes to Kotlin, Swift or a native dependency must pass on an
  iPhone and an Android phone before they reach `latest`. Open the pull request once CI is green.
  If you have a phone, run `scripts/device-diagnostics.sh` on it and add the results; either way,
  the maintainer tests on both phones before the release.
- **Features start as an issue.** Everything in [features.md](./features.md) changes the public
  API, so its shape is agreed in an issue before anyone writes code.

## Priorities and IDs

| Priority | Meaning |
| --- | --- |
| **P1** | Goes in the next release it fits |
| **P2** | Follow-up within 0.2.x or 0.3.0 |
| **P3** | Optional, later |

IDs never change. An item is deleted from here in the commit that completes it, so nothing listed
is already done; the changelog records it from then on.
