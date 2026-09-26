import type { PoseCameraRef } from 'react-native-pose-detection';

export type ScenarioReport = {
  readonly id: string;
  readonly passed: boolean;
  /** True when the scenario could not apply here, for example idle search with somebody in frame. */
  readonly skipped?: boolean;
  readonly iterations: number;
  readonly elapsedMs: number;
  readonly detail: string;
  readonly heapBefore: number | null;
  readonly heapAfter: number | null;
};

export type ScenarioContext = {
  /** Null between a remount's teardown and the next mount, which is a case every runner hits. */
  readonly camera: { current: PoseCameraRef | null };
  /** Unmounts and remounts the camera, resolving on the next `onReady` rather than on a timer. */
  readonly remount: () => Promise<void>;
  /**
   * Remounts without waiting for anything, resolving as soon as the new camera's ref exists. The
   * returned promise resolves on that mount's `onReady`, which is what a pause during startup races.
   */
  readonly remountNow: () => Promise<{ ready: Promise<void> }>;
  /** How many `onReady` events have fired. A restart emits another, which is how one is noticed. */
  readonly readyCount: () => number;
  /** The facing the last `onReady` or `onCameraChange` reported. */
  readonly facing: () => 'front' | 'back' | null;
  /** How many `onCameraChange` events have fired. */
  readonly cameraChanges: () => number;
  /**
   * Covers the camera with a full-screen modal for `ms`, which on iOS takes its view out of the
   * window and puts it back: the native-stack push and pop case, without a navigation library.
   */
  readonly cover: (ms: number) => Promise<void>;
  /** Flips a set of props that must never restart the camera: overlay, smoothing, data mode. */
  readonly toggleProps: () => void;
  /** A line in the scenario's own log, shown under the report. */
  readonly log: (line: string) => void;
};

export type Scenario = {
  readonly id: string;
  readonly title: string;
  readonly verifies: string;
  /** Long runs, left out of an `all` sweep and only run when named. */
  readonly slow?: boolean;
  readonly run: (context: ScenarioContext) => Promise<ScenarioReport>;
};
