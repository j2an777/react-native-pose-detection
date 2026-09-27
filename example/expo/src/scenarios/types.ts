import type { PoseCameraProps, PoseCameraRef } from 'react-native-pose-detection';

import type { DiagnosticsMedia } from '../diagnosticsRequest';

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
  /** The scenario's log lines, which iOS has no device log to show. */
  readonly log?: readonly string[];
};

export type CameraProps = Pick<
  PoseCameraProps,
  | 'triggers'
  | 'data'
  | 'onTrigger'
  | 'onPose'
  | 'onPoseBatch'
  | 'onFramesDropped'
  | 'onPerformanceChange'
>;

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
  /**
   * Unmounts the camera, runs `run` with no camera in the tree, and mounts it again, resolving on
   * that mount's `onReady`: what a screen without a camera, a studio or a settings page, looks like.
   */
  readonly withoutCamera: (run: () => Promise<void>) => Promise<void>;
  /** Flips a set of props that must never restart the camera: overlay, smoothing, data mode. */
  readonly toggleProps: () => void;
  /** Laid over the camera's own props; null takes them off again. */
  readonly setCameraProps: (props: CameraProps | null) => void;
  /** Large text over a full-size camera, for somebody standing back from the phone. */
  readonly prompt: (text: string | null) => void;
  /** Files the launch pointed at, for the scenarios that need a real photo or clip. */
  readonly media: DiagnosticsMedia;
  /** A line in the scenario's own log, shown under the report. */
  readonly log: (line: string) => void;
};

export type Scenario = {
  readonly id: string;
  readonly title: string;
  readonly verifies: string;
  /** Long runs, left out of an `all` sweep and only run when named. */
  readonly slow?: boolean;
  /** Needs somebody in front of the camera, so it is left out of an `all` sweep too. */
  readonly manual?: boolean;
  readonly run: (context: ScenarioContext) => Promise<ScenarioReport>;
};
