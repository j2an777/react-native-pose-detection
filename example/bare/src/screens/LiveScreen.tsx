import { Ionicons } from '@expo/vector-icons';
import { useKeepAwake } from 'expo-keep-awake';
import * as React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  addLogListener,
  PoseCamera,
  setLogLevel,
  useCameraPermission,
  type CameraChangeEvent,
  type ErrorEvent,
  type LogEntry,
  type PoseCameraRef,
  type ProfileState,
  type ReadyEvent,
} from 'react-native-pose-detection';

import { Button, Choice, IconButton, Rule, ToggleRow, type IconName } from '../components/Controls';
import { Card, Glass } from '../components/Glass';
import { Sheet } from '../components/Sheet';
import { theme } from '../theme';

type Category = 'camera' | 'detection' | 'debug';

const CATEGORIES: { id: Category; icon: IconName; label: string }[] = [
  { id: 'camera', icon: 'camera-outline', label: 'Camera' },
  { id: 'detection', icon: 'body-outline', label: 'Detection' },
  { id: 'debug', icon: 'pulse-outline', label: 'Debug' },
];

const RESOLUTIONS = ['auto', '480p', '720p', '1080p'] as const;
const ANALYSIS = ['auto', '360p', '480p', '720p'] as const;
const LOG_LEVELS = ['off', 'warn', 'info', 'debug'] as const;
const TARGET_FPS = ['auto', '10', '15', '24', '30'] as const;
const FACING = ['auto', 'front', 'back'] as const;
const DELEGATES = ['auto', 'gpu', 'cpu'] as const;
const PROFILES = ['auto', 'efficient', 'balanced', 'quality', 'unrestricted'] as const;
const THERMAL = ['adaptive', 'critical-only', 'off'] as const;
const DATA_MODES = ['off', 'throttled', 'batched', 'live'] as const;
const MAX_POSES = ['1', '2', '3', '4', '5'] as const;
// 'auto' passes nothing, which is what makes the package take the threshold from People: 0.6 for a
// single subject, 0.3 above that. The numbers override it, and 0.3 is the floor worth offering
// because below it the model returns the same body twice rather than finding a second one.
const CONFIDENCE = ['auto', '0.3', '0.4', '0.5', '0.6', '0.7'] as const;
// 'auto' is the package's own default: off for one pose, which MediaPipe already smooths, and on
// for several. 'on' adds the package's filter anyway, with the two numbers below. It is
// `cutoff = minCutoff + beta * speed`, speed in body spans per second: minCutoff sets how hard a
// still body is smoothed, beta how quickly that relaxes once it moves. 0.05 and 80 are MediaPipe's.
const SMOOTHING = ['auto', 'on', 'off'] as const;
const MIN_CUTOFF = ['0.01', '0.05', '0.2', '1'] as const;
const BETA = ['10', '40', '80', '160'] as const;

/**
 * What the angle toggle draws when it is on.
 *
 * Off is an empty list rather than a hidden one: native skips the whole angle pass when the config
 * carries none, so switching this off stops the trigonometry and the arcs rather than drawing them
 * somewhere nobody looks.
 */
const ANGLE_JOINTS = [
  { joint: 'leftElbow' },
  { joint: 'rightElbow' },
  { joint: 'leftKnee' },
  { joint: 'rightKnee' },
] as const;

/**
 * The camera, full bleed, with everything else floating at the edges.
 *
 * The controls are grouped into three categories rather than laid out as one long rail, because the
 * middle of the screen is the part that matters: it is where the person being detected is, and a
 * panel is only ever open while somebody is deliberately changing something. Opening one closes the
 * others, and tapping the preview closes all of them.
 */
export function LiveScreen({ onClose }: { onClose: () => void }) {
  const camera = React.useRef<PoseCameraRef>(null);
  const permission = useCameraPermission();
  const insets = useSafeAreaInsets();
  // Somebody doing a set stands back from the phone and stops touching it, and the screen would
  // lock, stopping the camera, partway through. The package leaves this to the app.
  useKeepAwake();

  const [panel, setPanel] = React.useState<Category | null>(null);

  const [overlay, setOverlay] = React.useState(true);
  const [landmarks, setLandmarks] = React.useState(true);
  const [connections, setConnections] = React.useState(true);
  const [detecting, setDetecting] = React.useState(true);
  const [active, setActive] = React.useState(true);
  const [facing, setFacing] = React.useState<'front' | 'back'>('front');
  const [resolution, setResolution] = React.useState<(typeof RESOLUTIONS)[number]>('auto');
  const [analysis, setAnalysis] = React.useState<(typeof ANALYSIS)[number]>('auto');
  const [targetFps, setTargetFps] = React.useState<(typeof TARGET_FPS)[number]>('auto');
  const [angles, setAngles] = React.useState(false);
  const [facingRequest, setFacingRequest] = React.useState<(typeof FACING)[number]>('auto');
  const [switching, setSwitching] = React.useState(false);
  const [delegate, setDelegate] = React.useState<(typeof DELEGATES)[number]>('auto');
  const [profile, setProfile] = React.useState<(typeof PROFILES)[number]>('auto');
  const [thermalPolicy, setThermalPolicy] = React.useState<(typeof THERMAL)[number]>('adaptive');
  const [dataMode, setDataMode] = React.useState<(typeof DATA_MODES)[number]>('off');
  const [maxPoses, setMaxPoses] = React.useState<(typeof MAX_POSES)[number]>('1');
  const [confidence, setConfidence] = React.useState<(typeof CONFIDENCE)[number]>('auto');
  const [smoothing, setSmoothing] = React.useState<(typeof SMOOTHING)[number]>('auto');
  const [minCutoff, setMinCutoff] = React.useState<(typeof MIN_CUTOFF)[number]>('0.05');
  const [beta, setBeta] = React.useState<(typeof BETA)[number]>('80');
  const [poseCount, setPoseCount] = React.useState(0);
  const [snapshot, setSnapshot] = React.useState<string | null>(null);
  /**
   * Counted in a ref and shown a few times a second. A state update per frame re-rendered this
   * whole screen thirty times a second, which is the JavaScript load the package is built to avoid.
   */
  const framesSeen = React.useRef(0);

  const [logLevel, setLevel] = React.useState<(typeof LOG_LEVELS)[number]>('off');
  const [showLogs, setShowLogs] = React.useState(false);
  const [showStats, setShowStats] = React.useState(true);
  const [lines, setLines] = React.useState<LogEntry[]>([]);

  const [ready, setReady] = React.useState<ReadyEvent | null>(null);
  const [measured, setMeasured] = React.useState<ProfileState | null>(null);
  const [notice, setNotice] = React.useState<{ message: string; fatal: boolean } | null>(null);

  /**
   * The measured rate changes every second and no event carries it: performance events fire when
   * the configuration moves, not when the measurement does. Polled, and only while a readout is on
   * screen, so a hidden stat bar and a closed debug panel cost nothing.
   */
  const reading = showStats || panel === 'debug';
  React.useEffect(() => {
    if (!reading || !ready) return;
    const read = () => {
      void camera.current
        ?.getProfile()
        .then(setMeasured)
        .catch(() => undefined);
    };
    // Once now, then on the interval: waiting a full period before the first read leaves the
    // readout at zero next to a skeleton that is already tracking.
    read();
    const poll = setInterval(read, FPS_POLL_MS);
    return () => clearInterval(poll);
  }, [reading, ready]);

  React.useEffect(() => {
    if (dataMode === 'off') return;
    const show = setInterval(() => setPoseCount(framesSeen.current), FRAME_COUNT_MS);
    return () => clearInterval(show);
  }, [dataMode]);

  const countFrame = React.useCallback(() => {
    framesSeen.current += 1;
  }, []);

  const onReady = React.useCallback((event: ReadyEvent) => {
    setReady(event);
    setNotice(null);
  }, []);
  const onError = React.useCallback((event: ErrorEvent) => {
    setNotice({ message: event.message, fatal: event.fatal });
  }, []);
  const onCameraChange = React.useCallback(
    (event: CameraChangeEvent) => setFacing(event.facing),
    [],
  );

  // The stream stays closed until somebody asks for a level, so an idle screen pays nothing for a
  // console it is not showing.
  React.useEffect(() => {
    setLogLevel(logLevel);
    if (logLevel === 'off') {
      setLines([]);
      return;
    }
    const subscription = addLogListener((entries) => {
      setLines((value) => [...value, ...entries].slice(-LOG_LIMIT));
    });
    return () => subscription.remove();
  }, [logLevel]);

  /**
   * Every ref method crosses to native and can fail, and a rejection nobody catches becomes a red
   * box over the camera. Switching lenses is the one people hit: a device with a single camera, or
   * one already mid-switch, rejects with `CAMERA_SWITCH_FAILED`.
   */
  /// Every ref method rejects rather than throwing, so one place turns that into the notice bar.
  /// Returns the promise so a caller that also has to know when the work ended can chain onto it.
  const call = React.useCallback((run: () => Promise<unknown> | undefined) => {
    return Promise.resolve(run()).catch((problem: unknown) => {
      setNotice({
        message: problem instanceof Error ? problem.message : String(problem),
        fatal: false,
      });
    });
  }, []);

  /**
   * `switchCamera()` resolves when the new lens actually delivers a frame, not when the request is
   * accepted, so awaiting it is what makes the pending state mean something. A second tap while one
   * is in flight is dropped rather than queued: the native side rebinds the session, and asking it
   * to rebind again mid-rebind is how a switch ends up failing for reasons nobody can see.
   */
  const flip = React.useCallback(() => {
    if (switching) return;
    setSwitching(true);
    void call(() => camera.current?.switchCamera()).finally(() => setSwitching(false));
  }, [call, switching]);

  if (!permission.granted) {
    return (
      <View style={[styles.gate, { paddingTop: insets.top }]}>
        <Card style={styles.gateCard} radius={theme.radius.lg}>
          <Text style={styles.gateTitle}>Camera access</Text>
          <Text style={styles.gateBody}>
            {permission.canAskAgain
              ? 'The preview needs the camera. Nothing leaves the device.'
              : 'Camera access was denied. Turn it back on in Settings to use the preview.'}
          </Text>
          {permission.canAskAgain ? (
            <Button title="Allow camera" onPress={() => void permission.request()} />
          ) : null}
        </Card>
      </View>
    );
  }

  const fps = measured?.measuredFps ?? 0;
  // Everything at the bottom stacks off one base, so a panel can never land under the rail.
  const railBottom = insets.bottom + theme.space(4);
  const panelBottom = railBottom + RAIL_HEIGHT + theme.space(3);
  const target = measured?.resolved.targetFps ?? ready?.targetFps ?? 0;

  return (
    <View style={styles.root}>
      <PoseCamera
        ref={camera}
        style={StyleSheet.absoluteFill}
        facing={facingRequest}
        active={active}
        detection={detecting}
        delegate={delegate}
        profile={profile}
        thermalPolicy={thermalPolicy}
        targetFps={targetFps === 'auto' ? undefined : Number(targetFps)}
        maxPoses={Number(maxPoses)}
        minConfidence={confidence === 'auto' ? undefined : Number(confidence)}
        smoothing={
          smoothing === 'on'
            ? { minCutoff: Number(minCutoff), beta: Number(beta) }
            : smoothing === 'auto'
            ? 'auto'
            : false
        }
        data={{ mode: dataMode }}
        onPose={dataMode === 'off' ? undefined : countFrame}
        resolution={resolution}
        analysisResolution={analysis}
        overlay={
          overlay && {
            color: theme.color.overlay,
            lineWidth: 3,
            pointRadius: 4,
            landmarks,
            connections,
            angles: angles ? ANGLE_JOINTS : [],
          }
        }
        onReady={onReady}
        onError={onError}
        onCameraChange={onCameraChange}
      />

      {/* Closes any open panel without stealing a tap that was meant for a control. */}
      {panel ? (
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={() => setPanel(null)}
          accessibilityRole="button"
          accessibilityLabel="Close the panel"
        />
      ) : null}

      <View style={[styles.top, { top: insets.top + theme.space(2) }]} pointerEvents="box-none">
        <IconButton icon="close" label="Leave the camera" onPress={onClose} size={42} />

        {showStats ? (
          <Glass style={styles.statBar} radius={theme.radius.pill} intensity={55}>
            <Live label="fps" value={ready ? `${fps}` : '–'} hint={target ? `/${target}` : ''} />
            <Divider />
            <Live label="limit" value={measured?.limitedBy ?? '–'} />
            <Divider />
            {/* The profile's, which follows Android's move from the CPU to the GPU after onReady. */}
            <Live label="gpu" value={measured?.resolved.delegate ?? ready?.delegate ?? '–'} />
            <Divider />
            {dataMode === 'off' ? (
              <Live label="in" value={shortSize(ready?.analysisResolution)} />
            ) : (
              <Live label="frames" value={String(poseCount)} />
            )}
          </Glass>
        ) : (
          <View style={styles.spacer} />
        )}
      </View>

      {notice ? (
        <View
          style={[styles.noticeWrap, { top: insets.top + theme.space(16) }]}
          pointerEvents="none"
        >
          <Glass style={styles.noticeCard} radius={theme.radius.md} intensity={55}>
            <Ionicons
              name={notice.fatal ? 'alert-circle' : 'information-circle-outline'}
              size={15}
              color={notice.fatal ? theme.color.danger : theme.color.muted}
            />
            <Text
              style={[styles.noticeText, notice.fatal && { color: theme.color.danger }]}
              numberOfLines={2}
            >
              {notice.message}
            </Text>
          </Glass>
        </View>
      ) : null}

      <View style={[styles.logWrap, { bottom: panelBottom }]} pointerEvents="box-none">
        <Sheet
          visible={showLogs && logLevel !== 'off' && !panel}
          style={styles.logCard}
          radius={theme.radius.md}
        >
          <ScrollView showsVerticalScrollIndicator={false}>
            {lines.length === 0 ? (
              <Text style={styles.logEmpty}>waiting for the first entry</Text>
            ) : (
              lines.map((entry, index) => (
                <Text key={`${entry.timestamp}-${index}`} style={styles.logLine} numberOfLines={1}>
                  <Text style={styles.logCategory}>{entry.category}</Text> {entry.message}
                </Text>
              ))
            )}
          </ScrollView>
        </Sheet>
      </View>

      <View style={[styles.panelWrap, { bottom: panelBottom }]} pointerEvents="box-none">
        <Sheet visible={panel !== null} style={styles.panel}>
          <ScrollView
            contentContainerStyle={styles.panelContent}
            showsVerticalScrollIndicator={false}
            bounces={false}
          >
            {panel === 'camera' ? (
              <>
                <ToggleRow title="Camera" value={active} onChange={setActive} />
                <Rule />
                <Choice
                  title="Lens"
                  options={FACING}
                  value={facingRequest}
                  onChange={setFacingRequest}
                />
                <Choice
                  title="Preview quality"
                  options={RESOLUTIONS}
                  value={resolution}
                  onChange={setResolution}
                />
                <Choice
                  title="What the model sees"
                  options={ANALYSIS}
                  value={analysis}
                  onChange={setAnalysis}
                />
                <Choice
                  title="Target frame rate"
                  options={TARGET_FPS}
                  value={targetFps}
                  onChange={setTargetFps}
                />
              </>
            ) : null}

            {panel === 'detection' ? (
              <>
                <ToggleRow
                  title="Inference"
                  value={detecting}
                  onChange={(next) => {
                    setDetecting(next);
                    void (next
                      ? camera.current?.startDetection()
                      : camera.current?.stopDetection());
                  }}
                />
                <ToggleRow title="Skeleton" value={overlay} onChange={setOverlay} />
                <ToggleRow title="Joints" value={landmarks} onChange={setLandmarks} />
                <ToggleRow title="Bones" value={connections} onChange={setConnections} />
                <ToggleRow title="Angles" value={angles} onChange={setAngles} />
                <Choice
                  title="Smoothing"
                  options={SMOOTHING}
                  value={smoothing}
                  onChange={setSmoothing}
                />
                {smoothing === 'on' ? (
                  <>
                    <Choice
                      title="Rest cutoff"
                      options={MIN_CUTOFF}
                      value={minCutoff}
                      onChange={setMinCutoff}
                    />
                    <Choice title="Speed response" options={BETA} value={beta} onChange={setBeta} />
                  </>
                ) : null}
                <Rule />
                <Choice
                  title="Delegate"
                  options={DELEGATES}
                  value={delegate}
                  onChange={setDelegate}
                />
                <Choice
                  title="People"
                  options={MAX_POSES}
                  value={maxPoses}
                  onChange={setMaxPoses}
                />
                <Choice
                  title="Confidence"
                  options={CONFIDENCE}
                  value={confidence}
                  onChange={setConfidence}
                />
                <Choice title="Profile" options={PROFILES} value={profile} onChange={setProfile} />
              </>
            ) : null}

            {panel === 'debug' ? (
              <>
                <Readout ready={ready} measured={measured} facing={facing} />
                <Rule />
                <ToggleRow title="Readout" value={showStats} onChange={setShowStats} />
                <Rule />
                <Choice
                  title="Log level"
                  options={LOG_LEVELS}
                  value={logLevel}
                  onChange={setLevel}
                />
                <ToggleRow title="Console" value={showLogs} onChange={setShowLogs} />
                <Rule />
                <Choice
                  title="Frames to JavaScript"
                  options={DATA_MODES}
                  value={dataMode}
                  onChange={(next) => {
                    framesSeen.current = 0;
                    setPoseCount(0);
                    setDataMode(next);
                  }}
                />
                <Choice
                  title="Thermal policy"
                  options={THERMAL}
                  value={thermalPolicy}
                  onChange={setThermalPolicy}
                />
                <Button
                  title={snapshot ?? 'Take a snapshot'}
                  tone="quiet"
                  onPress={() => {
                    void camera.current?.snapshot().then((frame) => {
                      setSnapshot(
                        frame ? `${frame.landmarks.length / 4} landmarks` : 'no pose in frame',
                      );
                    });
                  }}
                />
              </>
            ) : null}
          </ScrollView>
        </Sheet>
      </View>

      <View style={[styles.rail, { bottom: railBottom }]} pointerEvents="box-none">
        <Glass style={styles.railInner} radius={theme.radius.pill} intensity={60}>
          {CATEGORIES.map((category) => (
            <IconButton
              key={category.id}
              icon={category.icon}
              label={category.label}
              active={panel === category.id}
              onPress={() => setPanel((value) => (value === category.id ? null : category.id))}
            />
          ))}
        </Glass>
        {/* Its own container, because switching lenses is not one of the three things the panels
            configure: it acts immediately and belongs beside them rather than among them. */}
        <Glass style={styles.railInner} radius={theme.radius.pill} intensity={60}>
          <IconButton icon="sync-outline" label="Switch camera" busy={switching} onPress={flip} />
        </Glass>
      </View>
    </View>
  );
}

const LOG_LIMIT = 40;
/** The rail's own height, so the panel above it can be placed without measuring. */
const RAIL_HEIGHT = 58;
/** The native side refreshes its measurement once a second, so asking faster reads the same number. */
const FPS_POLL_MS = 1000;
/** Often enough to read as live, rarely enough that counting frames never costs a render per frame. */
const FRAME_COUNT_MS = 250;

/** `Resolution` is a width and a height, not the preset name that was asked for. */
function shortSize(size?: { width: number; height: number }) {
  return size ? `${Math.min(size.width, size.height)}p` : '–';
}

function Live({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <View style={styles.live}>
      <Text style={styles.liveValue} numberOfLines={1}>
        {value}
        {hint ? <Text style={styles.liveHint}>{hint}</Text> : null}
      </Text>
      <Text style={styles.liveLabel}>{label}</Text>
    </View>
  );
}

function Divider() {
  return <View style={styles.divider} />;
}

/**
 * Everything the governor knows, in one place: what it measured, what it chose, and why. What a
 * person needs to judge a device before and after a change, and to explain a rate below target.
 */
function Readout({
  ready,
  measured,
  facing,
}: {
  ready: ReadyEvent | null;
  measured: ProfileState | null;
  facing: 'front' | 'back';
}) {
  const rows: [string, string][] = [
    [
      'Rate',
      measured
        ? `${measured.measuredFps} of ${measured.resolved.targetFps} fps, camera ${measured.cameraFps}`
        : '–',
    ],
    ['Limited by', measured?.limitedBy ?? '–'],
    [
      'Inference p50',
      measured && measured.p50InferenceMs > 0 ? `${measured.p50InferenceMs.toFixed(1)} ms` : '–',
    ],
    ['Device', measured ? `${measured.tier} tier, ${measured.phase} (${measured.source})` : '–'],
    [
      'Heat',
      measured ? `${measured.thermalState}${measured.lowPower ? ', low power on' : ''}` : '–',
    ],
    ['Lens', facing],
    ['Model', ready ? `${ready.model} on ${measured?.resolved.delegate ?? ready.delegate}` : '–'],
    [
      'Frames',
      measured && ready
        ? `preview ${measured.resolved.preview}, model sees ${shortSize(ready.analysisResolution)}`
        : '–',
    ],
  ];
  return (
    <View style={styles.readout}>
      {rows.map(([label, value]) => (
        <View key={label} style={styles.readoutRow}>
          <Text style={styles.readoutLabel}>{label}</Text>
          <Text style={styles.readoutValue} numberOfLines={1}>
            {value}
          </Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  readout: {
    gap: theme.space(1.5),
    paddingVertical: theme.space(1),
  },
  readoutRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: theme.space(3),
  },
  readoutLabel: {
    color: theme.color.muted,
    fontSize: 13,
  },
  readoutValue: {
    flexShrink: 1,
    color: theme.color.text,
    fontSize: 13,
    fontVariant: ['tabular-nums'],
  },
  root: {
    flex: 1,
    backgroundColor: '#000',
  },
  top: {
    position: 'absolute',
    left: theme.space(4),
    right: theme.space(4),
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space(2),
  },
  spacer: {
    flex: 1,
  },
  statBar: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: theme.space(2),
    paddingHorizontal: theme.space(4),
  },
  live: {
    alignItems: 'center',
    minWidth: 42,
  },
  liveValue: {
    color: theme.color.text,
    fontSize: theme.font.label,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  liveHint: {
    color: theme.color.faint,
    fontWeight: '500',
  },
  liveLabel: {
    color: theme.color.faint,
    fontSize: 9,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
  },
  divider: {
    width: StyleSheet.hairlineWidth,
    alignSelf: 'stretch',
    backgroundColor: theme.color.border,
  },
  noticeWrap: {
    position: 'absolute',
    left: theme.space(4),
    right: theme.space(4),
  },
  noticeCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space(2),
    paddingVertical: theme.space(2.5),
    paddingHorizontal: theme.space(4),
  },
  noticeText: {
    color: theme.color.muted,
    fontSize: theme.font.tiny,
    flex: 1,
  },
  logWrap: {
    position: 'absolute',
    left: theme.space(4),
    right: theme.space(4),
  },
  logCard: {
    maxHeight: 160,
    padding: theme.space(3),
    backgroundColor: 'rgba(9,12,18,0.92)',
    borderColor: 'rgba(255,255,255,0.14)',
  },
  logLine: {
    color: 'rgba(236,240,246,0.82)',
    fontSize: 10,
    fontFamily: 'monospace',
    lineHeight: 15,
  },
  logCategory: {
    color: '#4DD8EE',
  },
  logEmpty: {
    color: 'rgba(236,240,246,0.45)',
    fontSize: 10,
    fontFamily: 'monospace',
  },
  panelWrap: {
    position: 'absolute',
    left: theme.space(4),
    right: theme.space(4),
  },
  panel: {
    maxHeight: 340,
  },
  panelContent: {
    padding: theme.space(5),
    gap: theme.space(4),
  },
  rail: {
    position: 'absolute',
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: theme.space(2.5),
  },
  railInner: {
    flexDirection: 'row',
    padding: theme.space(1.5),
    gap: theme.space(2),
  },
  gate: {
    flex: 1,
    backgroundColor: theme.color.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: theme.space(6),
  },
  gateCard: {
    padding: theme.space(6),
    gap: theme.space(3),
    width: '100%',
  },
  gateTitle: {
    color: theme.color.text,
    fontSize: theme.font.title,
    fontWeight: '700',
  },
  gateBody: {
    color: theme.color.muted,
    fontSize: theme.font.body,
    lineHeight: theme.font.body * 1.5,
  },
});
