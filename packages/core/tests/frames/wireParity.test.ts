import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { test } from 'node:test';

import {
  FRAME_META_FLOAT64S,
  HEADER_FLOAT64S,
  HEADER_INDEX,
  SCALARS_PER_FRAME,
  WIRE_FLAG_ANGLES,
  WIRE_FLAG_WORLD_LANDMARKS,
} from '../../src/frames/wire';
import { ANGLE_JOINTS, ANGLE_JOINT_NAMES, LANDMARK_COUNT } from '../../src/types/joints';
import { LANDMARK_STRIDE } from '../../src/types/frame';

/**
 * The wire format is written in TypeScript, Kotlin and Swift, and a stale constant decodes into
 * plausible wrong numbers. Files are found by name, so a reorganization does not break this.
 */
const CORE = resolve(__dirname, '../../..', 'packages/core');
const ROOTS = {
  kotlin: resolve(CORE, 'android/src/main/java'),
  swift: resolve(CORE, 'ios'),
} as const;

type Language = keyof typeof ROOTS;

function find(fileName: string, from: string): string | null {
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    const path = resolve(from, entry.name);
    if (entry.isDirectory()) {
      const found = find(fileName, path);
      if (found) return found;
    } else if (entry.name === fileName) {
      return path;
    }
  }
  return null;
}

function read(language: Language, fileName: string): string {
  const root = ROOTS[language];
  const path = find(fileName, root);
  assert.ok(
    path !== null,
    `${fileName} is not under ${root}. If it was renamed, update this test.`,
  );
  return readFileSync(path, 'utf8');
}

/** `const val NAME = 6` in Kotlin, `static let name = 6` in Swift; the value may be a shift. */
function constant(language: Language, source: string, name: string): number {
  const declaration =
    language === 'kotlin' ? `const val ${name} = ([^\\n]+)` : `static let ${name} = ([^\\n]+)`;
  const found = new RegExp(declaration).exec(source)?.[1];
  assert.ok(found !== undefined, `${name} is not declared in the ${language}`);

  const shifted = /^(\d+) (?:shl|<<) (\d+)$/.exec(found.trim());
  if (shifted) return Number(shifted[1]) << Number(shifted[2]);

  const value = Number(found.trim().replace(/_/g, ''));
  assert.ok(!Number.isNaN(value), `${name} is ${found.trim()}, which is not a number`);
  return value;
}

/** The two sides differ only in case: INDEX_FRAME_COUNT / indexFrameCount. */
const WIRE_NAMES = {
  kotlin: {
    header: 'HEADER_FLOAT64S',
    meta: 'FRAME_META_FLOAT64S',
    scalars: 'SCALARS_PER_FRAME',
    world: 'FLAG_WORLD_LANDMARKS',
    angles: 'FLAG_ANGLES',
    index: (slot: string) =>
      `INDEX_${slot.replace(/[A-Z]/g, (letter) => `_${letter}`).toUpperCase()}`,
    count: 'LANDMARK_COUNT',
    stride: 'LANDMARK_STRIDE',
    file: 'FrameWire.kt',
    skeleton: 'Skeleton.kt',
  },
  swift: {
    header: 'headerFloat64s',
    meta: 'frameMetaFloat64s',
    scalars: 'scalarsPerFrame',
    world: 'flagWorldLandmarks',
    angles: 'flagAngles',
    index: (slot: string) => `index${slot.charAt(0).toUpperCase()}${slot.slice(1)}`,
    count: 'landmarkCount',
    stride: 'landmarkStride',
    file: 'FrameWire.swift',
    skeleton: 'Skeleton.swift',
  },
} as const;

const LANGUAGES: readonly Language[] = ['kotlin', 'swift'];

for (const language of LANGUAGES) {
  const names = WIRE_NAMES[language];

  test(`the ${language} header layout matches wire.ts`, () => {
    const source = read(language, names.file);

    assert.equal(constant(language, source, names.header), HEADER_FLOAT64S);
    assert.equal(constant(language, source, names.meta), FRAME_META_FLOAT64S);
    assert.equal(constant(language, source, names.scalars), SCALARS_PER_FRAME);
    assert.equal(constant(language, source, names.world), WIRE_FLAG_WORLD_LANDMARKS);
    assert.equal(constant(language, source, names.angles), WIRE_FLAG_ANGLES);
  });

  test(`every header slot sits at the same index in the ${language}`, () => {
    const source = read(language, names.file);

    for (const [slot, index] of Object.entries(HEADER_INDEX)) {
      assert.equal(
        constant(language, source, names.index(slot)),
        index,
        `${slot} is at a different index`,
      );
    }

    // A slot count that no longer covers every named slot would silently truncate the header.
    assert.equal(Object.keys(HEADER_INDEX).length, HEADER_FLOAT64S);
  });

  test(`the ${language} skeleton is the same size and stride`, () => {
    const source = read(language, names.skeleton);

    assert.equal(constant(language, source, names.count), LANDMARK_COUNT);
    assert.equal(constant(language, source, names.stride), LANDMARK_STRIDE);
  });
}

test('angles are declared in the same order on all three sides', () => {
  const kotlin = read('kotlin', 'Skeleton.kt');
  const table = /ANGLE_TRIPLES: Map<String, IntArray> =\s*mapOf\(([\s\S]*?)\n {8}\)/.exec(
    kotlin,
  )?.[1];
  assert.ok(table !== undefined, 'could not find ANGLE_TRIPLES in the Kotlin');

  // Order is the contract: the angle block carries no names, only floats in this sequence.
  const kotlinJoints = [...table.matchAll(/"(\w+)" to intArrayOf/g)].map((match) => match[1]);
  assert.deepEqual(kotlinJoints, [...ANGLE_JOINT_NAMES]);

  const swift = read('swift', 'Skeleton.swift');
  const swiftJoints = [...swift.matchAll(/\("(\w+)", \[/g)].map((match) => match[1]);
  assert.deepEqual(swiftJoints, [...ANGLE_JOINT_NAMES]);
});

test('each angle is measured between the same three joints in the Kotlin', () => {
  const source = read('kotlin', 'Skeleton.kt');

  // Kotlin names landmarks in SCREAMING_SNAKE_CASE; TypeScript in camelCase.
  const toKotlin = (joint: string): string =>
    joint.replace(/[A-Z]/g, (letter) => `_${letter}`).toUpperCase();

  for (const [joint, triple] of Object.entries(ANGLE_JOINTS)) {
    const found = new RegExp(`"${joint}" to intArrayOf\\(([^)]+)\\)`).exec(source)?.[1];
    assert.ok(found !== undefined, `${joint} has no triple in the Kotlin`);

    const kotlinTriple = found.split(',').map((name) => name.trim());
    assert.deepEqual(kotlinTriple, triple.map(toKotlin), `${joint} is measured differently`);

    // And each of those names is a real landmark index, not a typo that happens to compile.
    for (const name of kotlinTriple) {
      assert.match(source, new RegExp(`const val ${name} = \\d+`), `${name} is not a landmark`);
    }
  }
});

test('each angle is measured between the same three joints in the Swift', () => {
  const source = read('swift', 'Skeleton.swift');

  for (const [joint, triple] of Object.entries(ANGLE_JOINTS)) {
    const found = new RegExp(`\\("${joint}", \\[([^\\]]+)\\]\\)`).exec(source)?.[1];
    assert.ok(found !== undefined, `${joint} has no triple in the Swift`);

    // Swift names landmarks in camelCase, exactly as TypeScript does, so these compare directly.
    const swiftTriple = found.split(',').map((name) => name.trim());
    assert.deepEqual(swiftTriple, [...triple], `${joint} is measured differently`);

    for (const name of swiftTriple) {
      assert.match(source, new RegExp(`static let ${name} = \\d+`), `${name} is not a landmark`);
    }
  }
});

test('the MediaPipe version in each calibration cache key is the one the build pins', () => {
  const gradle = readFileSync(resolve(CORE, 'android/build.gradle'), 'utf8');
  const podspec = readFileSync(resolve(CORE, 'ios/ReactNativePoseDetection.podspec'), 'utf8');
  const pinnedByGradle = /com\.google\.mediapipe:tasks-vision:([\d.]+)/.exec(gradle)?.[1];
  const pinnedByPod = /'MediaPipeTasksVision', '([\d.]+)'/.exec(podspec)?.[1];
  assert.ok(pinnedByGradle !== undefined, 'build.gradle no longer pins tasks-vision');
  assert.equal(pinnedByPod, pinnedByGradle, 'the two platforms pin different MediaPipe releases');

  // The calibration cache is keyed on these: a stale one would carry old costs across an upgrade.
  const kotlin = /const val PINNED = "([\d.]+)"/.exec(read('kotlin', 'Calibrator.kt'))?.[1];
  const swift = /static let pinned = "([\d.]+)"/.exec(read('swift', 'Calibrator.swift'))?.[1];
  assert.equal(kotlin, pinnedByGradle, 'MediaPipeVersion.PINNED is stale in the Kotlin');
  assert.equal(swift, pinnedByGradle, 'MediaPipeVersion.pinned is stale in the Swift');
});
