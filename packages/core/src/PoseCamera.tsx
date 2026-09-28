import * as React from 'react';

import { decodeFrames } from './frames/decodeFrames';
import type { DecodeOptions } from './frames/decodeFrames';
import { getNativeModule, getNativeView } from './native';
import type { NativePoseCameraView, NativeTriggerEvent } from './native';
import { callView } from './native/viewCalls';
import type { AngleJointName, JointName } from './types/joints';
import { ANGLE_JOINT_NAMES } from './types/joints';
import type { CameraState, LimitedBy, ProfileState } from './types/camera';
import type { PoseCameraProps, PoseCameraRef } from './types/props';
import { resolveSmoothing } from './smoothing';
import type { CameraChangeEvent, ErrorEvent, PerformanceEvent, ReadyEvent } from './types/events';
import type { LogEntry } from './types/logging';
import type { Condition, TriggerEvent } from './types/triggers';
import { emitLogEntries, holdLogStream } from './logging';
import {
  assertValidCameraNumbers,
  assertValidDataConfig,
  assertValidLogLevel,
  assertValidTriggers,
} from './validation';
import { resolveAngleJoints } from './frames/wire';

type NativeEvent<T> = { nativeEvent: T };

const NO_ANGLES: readonly AngleJointName[] = Object.freeze([]);

/** Every mounted camera gets its own id, which is how the frame reads find its ring buffer. */
let nextStreamId = 1;

/** Only an `angle` condition needs an angle. A joint used as a bound is a position. */
function collectAngleJoints(condition: Condition, into: Set<string>): void {
  const record = condition as Record<string, unknown>;

  const angle = record['angle'];
  if (typeof angle === 'string') into.add(angle);

  for (const key of ['all', 'any'] as const) {
    const members = record[key];
    if (Array.isArray(members)) {
      for (const member of members) collectAngleJoints(member as Condition, into);
    }
  }
}

/**
 * One frozen array while the contents are unchanged: these props are usually inline literals, and
 * the accessor cache keys on `PoseFrame.selection` by identity.
 */
function useStableList<T extends string>(value: readonly T[] | undefined): readonly T[] {
  const key = value === undefined ? '' : value.join(' ');
  const held = React.useRef<{ key: string; list: readonly T[] } | null>(null);

  if (held.current === null || held.current.key !== key) {
    held.current = { key, list: Object.freeze(value === undefined ? [] : [...value]) };
  }
  return held.current.list;
}

export const PoseCamera = React.forwardRef<PoseCameraRef, PoseCameraProps>(function PoseCamera(
  props,
  ref,
) {
  const NativeView = getNativeView();
  const nativeRef = React.useRef<NativePoseCameraView | null>(null);
  const [streamId] = React.useState(() => nextStreamId++);

  const { triggers, data, overlay, active, detection } = props;

  // During render, so a bad config fails at the call site. The validator's depth limit is also
  // what keeps the walk below finite on a cyclic config.
  if (triggers && triggers.length > 0) assertValidTriggers(triggers);
  assertValidCameraNumbers(props);
  assertValidDataConfig(props.data);
  if (props.logLevel !== undefined) assertValidLogLevel(props.logLevel);

  const requestedAngles = data?.angles;
  const angleJoints = useStableList<AngleJointName>(
    React.useMemo(() => {
      if (requestedAngles === true) return ANGLE_JOINT_NAMES;

      const referenced = new Set<string>(Array.isArray(requestedAngles) ? requestedAngles : []);
      for (const trigger of triggers ?? []) {
        collectAngleJoints(trigger.enter, referenced);
        if (trigger.exit) collectAngleJoints(trigger.exit, referenced);
      }
      if (typeof overlay === 'object' && overlay?.angles) {
        for (const arc of overlay.angles) referenced.add(arc.joint);
      }
      return referenced.size === 0 ? NO_ANGLES : resolveAngleJoints(referenced);
    }, [requestedAngles, triggers, overlay]),
  );

  // Exactly `data.select`: angles never widen it. See ADR 0005.
  const selected = useStableList<JointName>(data?.select);
  const selection = selected.length > 0 ? selected : undefined;

  const decodeOptions = React.useRef<DecodeOptions>({ angleJoints });
  const callbacks = React.useRef(props);
  const state = React.useRef<CameraState>({
    facing: 'front',
    active: active !== false,
    detecting: detection !== false,
    fps: 0,
    delegate: 'CPU',
    deviceTier: 'medium',
    limitedBy: 'paused',
  });

  React.useEffect(() => {
    decodeOptions.current = { angleJoints, ...(selection ? { selection } : {}) };
    callbacks.current = props;
  });

  React.useEffect(() => {
    state.current = { ...state.current, active: active !== false, detecting: detection !== false };
  }, [active, detection]);

  React.useEffect(() => {
    if (!__DEV__) return;
    const { onPose, onPoseBatch } = callbacks.current;
    const mode = data?.mode ?? 'off';
    if (mode === 'batched' && onPose && !onPoseBatch) {
      console.warn(
        "react-native-pose-detection: data.mode is 'batched', which delivers onPoseBatch. " +
          'onPose will not fire.',
      );
    }
    if (mode !== 'batched' && mode !== 'off' && onPoseBatch && !onPose) {
      console.warn(
        `react-native-pose-detection: data.mode is '${mode}', which delivers onPose. ` +
          'onPoseBatch will not fire.',
      );
    }
  }, [data]);

  const reportDecodeError = React.useCallback((message: string) => {
    callbacks.current.onError?.({ code: 'DETECTION_FAILED', message, fatal: false });
  }, []);

  const mounted = React.useRef(true);

  React.useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Synchronous on this thread, so two drains never overlap or deliver out of order.
  const handleFrames = React.useCallback(() => {
    if (!mounted.current) return;
    try {
      const buffer = getNativeModule().drainFrames(streamId);
      const { frames, droppedCount, error, stale } = decodeFrames(buffer, decodeOptions.current);
      if (stale) return;
      if (error) {
        reportDecodeError(error);
        return;
      }
      if (droppedCount > 0) callbacks.current.onFramesDropped?.(droppedCount);
      if (frames.length === 0) return;

      const { data: config, onPose, onPoseBatch } = callbacks.current;
      if (config?.mode === 'batched') {
        onPoseBatch?.(frames);
      } else if (onPose) {
        // A drain can carry more than one when the JavaScript thread was busy.
        for (const frame of frames) onPose(frame);
      }
    } catch (cause) {
      reportDecodeError(cause instanceof Error ? cause.message : 'draining frames failed');
    }
  }, [reportDecodeError, streamId]);

  const handleTrigger = React.useCallback(
    (event: NativeEvent<NativeTriggerEvent>) => {
      const { snapshotId, ...rest } = event.nativeEvent;
      const deliver = (trigger: TriggerEvent): void => callbacks.current.onTrigger?.(trigger);

      if (snapshotId === undefined) {
        deliver(rest);
        return;
      }
      let frame;
      try {
        const buffer = getNativeModule().takeTriggerSnapshot(streamId, snapshotId);
        frame = decodeFrames(buffer, decodeOptions.current).frames[0];
      } catch {
        frame = undefined;
      }
      deliver(frame ? { ...rest, snapshot: frame } : rest);
    },
    [streamId],
  );

  React.useImperativeHandle(
    ref,
    (): PoseCameraRef => {
      // A view command made before Fabric has mounted the native view waits for it, not fails.
      const view = <Result,>(invoke: (native: NativePoseCameraView) => Promise<Result>) =>
        callView(() => nativeRef.current, invoke);
      return {
        switchCamera: async () => {
          await view((native) => native.switchCamera());
        },
        setFacing: async (facing) => {
          await view((native) => native.setFacing(facing));
        },
        pause: async () => {
          await view((native) => native.pause());
          state.current = { ...state.current, active: false };
        },
        resume: async () => {
          await view((native) => native.resume());
          state.current = { ...state.current, active: true };
        },
        startDetection: async () => {
          await view((native) => native.startDetection());
          state.current = { ...state.current, detecting: true };
        },
        stopDetection: async () => {
          await view((native) => native.stopDetection());
          state.current = { ...state.current, detecting: false };
        },
        setOverlayEnabled: async (enabled) => {
          await view((native) => native.setOverlayEnabled(enabled));
        },
        setProfile: (profile) => {
          void view((native) => native.setProfile(profile));
        },
        getProfile: async () => {
          if (!nativeRef.current) throw new Error('The camera is not mounted yet.');
          const profile = await view((native) => native.getProfile() as Promise<ProfileState>);
          if (!profile) throw new Error('The camera was unmounted before it answered.');
          return profile;
        },
        getState: () => {
          const live = getNativeModule().readLiveState(streamId);
          return {
            ...state.current,
            ...(typeof live.fps === 'number' ? { fps: live.fps } : {}),
            ...(typeof live.limitedBy === 'string'
              ? { limitedBy: live.limitedBy as LimitedBy }
              : {}),
          };
        },
        snapshot: async () => {
          const buffer = getNativeModule().snapshotFrame(streamId);
          const { frames, error, stale } = decodeFrames(buffer, decodeOptions.current);
          if (stale) return null;
          if (error) throw new Error(error);
          return frames[0] ?? null;
        },
      };
    },
    [streamId],
  );

  const handleReady = React.useCallback((event: NativeEvent<ReadyEvent>) => {
    const ready = event.nativeEvent;
    state.current = {
      ...state.current,
      facing: ready.facing,
      active: true,
      delegate: ready.delegate,
      deviceTier: ready.deviceTier,
      limitedBy: ready.limitedBy,
    };
    callbacks.current.onReady?.(ready);
  }, []);

  const handleError = React.useCallback((event: NativeEvent<ErrorEvent>) => {
    const { code, fatal } = event.nativeEvent;
    // Fatal to detection only: the preview keeps running.
    if (fatal && code !== 'DETECTOR_INIT_FAILED')
      state.current = { ...state.current, active: false };
    callbacks.current.onError?.(event.nativeEvent);
  }, []);

  const handleCameraChange = React.useCallback((event: NativeEvent<CameraChangeEvent>) => {
    state.current = { ...state.current, facing: event.nativeEvent.facing };
    callbacks.current.onCameraChange?.(event.nativeEvent);
  }, []);

  const handlePerformanceChange = React.useCallback((event: NativeEvent<PerformanceEvent>) => {
    const performance = event.nativeEvent;
    state.current = {
      ...state.current,
      fps: performance.actualFps,
      delegate: performance.delegate,
      limitedBy: performance.limitedBy,
    };
    callbacks.current.onPerformanceChange?.(performance);
  }, []);

  const logs = props.onLog !== undefined;
  React.useEffect(() => (logs ? holdLogStream() : undefined), [logs]);

  // One native stream feeds both the prop and the global `addLogListener()` registry.
  const handleLog = React.useCallback((event: NativeEvent<{ entries: LogEntry[] }>) => {
    const { entries } = event.nativeEvent;
    callbacks.current.onLog?.(entries);
    emitLogEntries(entries);
  }, []);

  // Listed, not spread: `onPose`, `onPoseBatch` and `onFramesDropped` have no native counterpart.
  return (
    <NativeView
      style={props.style}
      profile={props.profile}
      facing={props.facing}
      delegate={props.delegate}
      // Native takes an integer: 'auto' would fail to convert and keep the last explicit rate.
      // Absent is what native reads as `auto`.
      targetFps={props.targetFps === 'auto' ? undefined : props.targetFps}
      streamId={streamId}
      resolution={props.resolution}
      analysisResolution={props.analysisResolution}
      thermalPolicy={props.thermalPolicy}
      maxPoses={props.maxPoses}
      minConfidence={props.minConfidence}
      smoothing={resolveSmoothing(props.smoothing, props.maxPoses)}
      active={active}
      detection={detection}
      overlay={overlay}
      data={data}
      triggers={triggers}
      logLevel={props.logLevel}
      angleJoints={angleJoints}
      selection={selection}
      ref={nativeRef as unknown as React.Ref<unknown>}
      onFrames={handleFrames}
      onReady={handleReady}
      onError={handleError}
      onCameraChange={handleCameraChange}
      onPerformanceChange={handlePerformanceChange}
      onTrigger={handleTrigger}
      onLog={handleLog}
    />
  );
});
