import type { PoseCameraProps, PoseCameraRef } from 'react-native-pose-detection';

import type { DiagnosticsMedia } from '../diagnosticsRequest';

export type ScenarioReport = {
  readonly id: string;
  readonly passed: boolean;
  /** Could not apply here, for example idle search with somebody in frame. */
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
  /** Null between a remount's teardown and the next mount. */
  readonly camera: { current: PoseCameraRef | null };
  /** Unmounts and remounts the camera, resolving on the next `onReady` rather than on a timer. */
  readonly remount: () => Promise<void>;
  /** Resolves as soon as the new camera's ref exists; `ready` resolves on its `onReady`. */
  readonly remountNow: () => Promise<{ ready: Promise<void> }>;
  /** A restart emits another `onReady`, which is how one is noticed. */
  readonly readyCount: () => number;
  /** The facing the last `onReady` or `onCameraChange` reported. */
  readonly facing: () => 'front' | 'back' | null;
  readonly cameraChanges: () => number;
  /** A full-screen modal over the camera for `ms`: the native-stack push and pop case on iOS. */
  readonly cover: (ms: number) => Promise<void>;
  /** Runs `run` with the camera unmounted, then remounts and resolves on its `onReady`. */
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
