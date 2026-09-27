import { File, Paths } from 'expo-file-system';
import { useKeepAwake } from 'expo-keep-awake';
import * as React from 'react';
import { Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  addLogListener,
  PoseCamera,
  requestCameraPermission,
  setLogLevel,
  type CameraChangeEvent,
  type PoseCameraRef,
  type ProfileState,
  type ReadyEvent,
} from 'react-native-pose-detection';

import { Button } from '../components/Controls';
import { Glass } from '../components/Glass';
import type { DiagnosticsRequest } from '../diagnosticsRequest';
import { EXTERNAL, SCENARIOS, type CameraProps, type ScenarioReport } from '../scenarios';
import { theme } from '../theme';

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Printed before every line an automated run logs, so a device log can be filtered to them. */
const LOG_TAG = 'POSE_DIAG';

/** Waits up to 10 s for calibration, then takes what it has: a first run can still be measuring. */
async function settledProfile(camera: React.RefObject<PoseCameraRef | null>) {
  const deadline = Date.now() + 10_000;
  let latest: ProfileState | null = null;
  while (Date.now() < deadline) {
    latest = (await camera.current?.getProfile().catch(() => null)) ?? latest;
    if (latest && latest.phase !== 'calibrating' && latest.measuredFps > 0) return latest;
    await sleep(250);
  }
  return latest;
}

/** `buffers` is the size frames really arrive at, from the camera log (iOS only). */
function describeDevice(
  profile: ProfileState,
  ready: ReadyEvent | null,
  buffers: string | null,
): string {
  const asked = ready
    ? `, analysis ${ready.analysisResolution.width}x${ready.analysisResolution.height}`
    : '';
  const analysis = `${asked}${buffers ? `, buffers ${buffers}` : ''}`;
  return (
    `${ready?.model ?? 'unknown'} model on ${profile.resolved.delegate}, ` +
    `p50 ${profile.p50InferenceMs.toFixed(1)} ms (${profile.source}` +
    `${profile.phase === 'calibrating' ? ', still calibrating' : ''}), ` +
    `${profile.measuredFps}/${profile.resolved.targetFps} fps (${profile.limitedBy}), ` +
    `camera ${profile.cameraFps} fps, ${profile.tier} tier, heat ${profile.thermalState}` +
    `${profile.lowPower ? ', low power' : ''}${analysis}`
  );
}

/**
 * The device regression harness for docs/testing.md. A sweep a launch asked for (see
 * `diagnosticsRequest`) writes its reports to `diagnostics.json` in the documents directory.
 */
export function DiagnosticsScreen({
  onClose,
  autoRun,
}: {
  onClose: () => void;
  autoRun?: DiagnosticsRequest | null;
}) {
  const camera = React.useRef<PoseCameraRef>(null);
  const [generation, setGeneration] = React.useState(0);
  const [running, setRunning] = React.useState<string | null>(null);
  const [reports, setReports] = React.useState<Record<string, ScenarioReport>>({});
  const [lines, setLines] = React.useState<string[]>([]);
  const [covered, setCovered] = React.useState(false);
  const [detached, setDetached] = React.useState(false);
  const [variant, setVariant] = React.useState(false);
  const [finished, setFinished] = React.useState<string | null>(null);
  const [cameraProps, setCameraProps] = React.useState<CameraProps | null>(null);
  const [prompt, setPrompt] = React.useState<string | null>(null);
  const ready = React.useRef<(() => void) | null>(null);
  const readyCount = React.useRef(0);
  const facing = React.useRef<'front' | 'back' | null>(null);
  const cameraChanges = React.useRef(0);
  const lastReady = React.useRef<ReadyEvent | null>(null);
  const insets = useSafeAreaInsets();
  // A sweep runs for minutes untouched, and a screen lock would stop the camera partway.
  useKeepAwake();

  const onReady = React.useCallback((event: ReadyEvent) => {
    readyCount.current += 1;
    facing.current = event.facing;
    lastReady.current = event;
    ready.current?.();
    ready.current = null;
  }, []);

  const onCameraChange = React.useCallback((event: CameraChangeEvent) => {
    cameraChanges.current += 1;
    facing.current = event.facing;
  }, []);

  const remount = React.useCallback(
    () =>
      new Promise<void>((resolve, reject) => {
        // A camera that never comes up fails its scenario rather than stalling the sweep.
        const timer = setTimeout(() => {
          ready.current = null;
          reject(new Error('the camera did not report ready within 10 s'));
        }, 10_000);
        ready.current = () => {
          clearTimeout(timer);
          resolve();
        };
        setGeneration((value) => value + 1);
      }),
    [],
  );

  const remountNow = React.useCallback(async () => {
    const previous = camera.current;
    const readyPromise = new Promise<void>((resolve) => {
      ready.current = resolve;
    });
    setGeneration((value) => value + 1);
    // The new camera's ref replaces the old one on commit, which is before its native view is up.
    for (
      let tries = 0;
      tries < 200 && (camera.current === previous || !camera.current);
      tries += 1
    ) {
      await sleep(5);
    }
    return { ready: readyPromise };
  }, []);

  const withoutCamera = React.useCallback(async (run: () => Promise<void>) => {
    setDetached(true);
    // The unmount commits and the native view leaves the window before anything runs.
    await sleep(400);
    try {
      await run();
    } finally {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
          ready.current = null;
          reject(new Error('the camera did not report ready within 10 s'));
        }, 10_000);
        ready.current = () => {
          clearTimeout(timer);
          resolve();
        };
        setDetached(false);
      });
    }
  }, []);

  const cover = React.useCallback(async (ms: number) => {
    setCovered(true);
    await sleep(ms);
    setCovered(false);
    // The dismissal animation, so the camera is back in the window before anything is measured.
    await sleep(400);
  }, []);

  const log = React.useCallback((line: string) => {
    // The automated sweep is read from the device's log, which is what console.log writes to.
    // eslint-disable-next-line no-console
    console.log(`${LOG_TAG} ${line}`);
    setLines((value) => [...value, line]);
  }, []);

  const runOne = React.useCallback(
    async (id: string) => {
      const scenario = SCENARIOS.find((item) => item.id === id);
      if (!scenario) return null;
      setRunning(id);
      setLines([]);
      const lines: string[] = [];
      try {
        const report = await scenario.run({
          camera,
          remount,
          remountNow,
          readyCount: () => readyCount.current,
          facing: () => facing.current,
          cameraChanges: () => cameraChanges.current,
          cover,
          withoutCamera,
          toggleProps: () => setVariant((value) => !value),
          setCameraProps,
          prompt: setPrompt,
          media: autoRun?.media ?? {},
          log: (line) => {
            lines.push(line);
            log(line);
          },
        });
        const logged = { ...report, log: lines };
        setReports((value) => ({ ...value, [id]: logged }));
        return logged;
      } catch (problem) {
        log(`${id} threw: ${String(problem)}`);
        return null;
      } finally {
        setRunning(null);
      }
    },
    [autoRun, cover, log, remount, remountNow, withoutCamera],
  );

  React.useEffect(() => {
    if (!autoRun) return;
    let cancelled = false;
    // Listens to the camera log for the real buffer size, and only until the scenarios start.
    let buffers: string | null = null;
    setLogLevel({ camera: 'info' });
    const subscription = addLogListener((entries) => {
      for (const entry of entries) {
        const size = /analysis buffers arrive at (\d+x\d+)/.exec(entry.message)?.[1];
        if (size) buffers = size;
      }
    });
    void (async () => {
      const collected: ScenarioReport[] = [];
      let ids: string[] = [];
      let device = null;
      // Prompts only when the system still will. Denied, the sweep fails once, not per scenario.
      const permission = await requestCameraPermission();
      if (permission.status !== 'granted') {
        const detail = `the camera permission is ${permission.status}: grant it, then run again`;
        collected.push({
          id: 'permission',
          passed: false,
          iterations: 0,
          elapsedMs: 0,
          detail,
          heapBefore: null,
          heapAfter: null,
        });
        log(`FAIL permission 0 ms · ${detail}`);
      } else {
        // Let the first mount come up before anything is timed against it.
        await sleep(2_500);
        const profile = await settledProfile(camera);
        device = profile
          ? {
              summary: describeDevice(profile, lastReady.current, buffers),
              profile,
              ready: lastReady.current,
              buffers,
            }
          : null;
        if (device) log(`device ${device.summary}`);
        ids =
          autoRun.scenarios === 'all'
            ? SCENARIOS.filter((item) => !item.slow && !item.manual).map((item) => item.id)
            : autoRun.scenarios.filter((id) => SCENARIOS.some((item) => item.id === id));
      }
      subscription.remove();
      setLogLevel('off');
      for (const id of ids) {
        if (cancelled) return;
        log(`start ${id}`);
        const report = await runOne(id);
        if (report) {
          collected.push(report);
          log(
            `${report.skipped ? 'SKIP' : report.passed ? 'PASS' : 'FAIL'} ${id} ` +
              `${Math.round(report.elapsedMs)} ms · ${report.detail}`,
          );
        }
      }
      const summary = {
        platform: Platform.OS,
        version: Platform.Version,
        finishedAt: new Date().toISOString(),
        device,
        reports: collected,
      };
      try {
        const file = new File(Paths.document, 'diagnostics.json');
        file.write(JSON.stringify(summary, null, 2));
      } catch (problem) {
        log(`could not write the report: ${String(problem)}`);
      }
      const failed = collected.filter((report) => !report.passed).length;
      log(`DONE ${collected.length} scenarios, ${failed} failed`);
      setFinished(`${collected.length} scenarios, ${failed} failed`);
    })();
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, [autoRun, log, runOne]);

  return (
    <View style={[styles.root, { paddingTop: insets.top + theme.space(3) }]}>
      <View style={styles.head}>
        <Text style={styles.title}>Diagnostics</Text>
        <Pressable onPress={onClose} accessibilityRole="button">
          <Text style={styles.close}>Done</Text>
        </Pressable>
      </View>

      <View style={[styles.stage, prompt !== null && styles.stageFull]}>
        {detached ? null : (
          <PoseCamera
            key={generation}
            ref={camera}
            style={StyleSheet.absoluteFill}
            delegate={autoRun?.delegate ?? 'auto'}
            // Flipped by the prop-toggle scenario: every one of these must be applied in place.
            overlay={{ color: theme.color.accent, angles: variant ? [{ joint: 'leftKnee' }] : [] }}
            smoothing={variant ? { minCutoff: 0.05, beta: 80 } : 'auto'}
            data={{ mode: variant ? 'throttled' : 'off' }}
            onPose={variant ? () => undefined : undefined}
            onReady={onReady}
            onCameraChange={onCameraChange}
            {...cameraProps}
          />
        )}
        {prompt !== null ? (
          <View style={styles.promptPlate} pointerEvents="none">
            <Text style={styles.prompt}>{prompt}</Text>
          </View>
        ) : null}
      </View>

      {finished ? <Text style={styles.finished}>Automated run finished: {finished}</Text> : null}

      <ScrollView
        style={prompt !== null && styles.hidden}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
      >
        {SCENARIOS.map((scenario) => {
          const report = reports[scenario.id];
          return (
            <Glass key={scenario.id} style={styles.card} radius={theme.radius.md} intensity={20}>
              <View style={styles.cardHead}>
                <View style={styles.cardText}>
                  <Text style={styles.cardTitle}>{scenario.title}</Text>
                  <Text style={styles.cardBody}>{scenario.verifies}</Text>
                </View>
                <Button
                  title={running === scenario.id ? 'Running' : 'Run'}
                  tone="quiet"
                  busy={running === scenario.id}
                  disabled={running !== null}
                  onPress={() => void runOne(scenario.id)}
                />
              </View>
              {report ? (
                <Text
                  style={[
                    styles.report,
                    { color: report.passed ? theme.color.good : theme.color.danger },
                  ]}
                >
                  {report.skipped ? 'skipped' : report.passed ? 'passed' : 'failed'} ·{' '}
                  {report.iterations} iterations · {Math.round(report.elapsedMs)} ms ·{' '}
                  {report.detail}
                </Text>
              ) : null}
            </Glass>
          );
        })}

        <Text style={styles.section}>Run these from the host</Text>
        {EXTERNAL.map((item) => (
          <Glass key={item.title} style={styles.card} radius={theme.radius.md} intensity={20}>
            <Text style={styles.cardTitle}>{item.title}</Text>
            <Text style={styles.cardBody}>{item.verifies}</Text>
            <Text style={styles.command}>{Platform.OS === 'ios' ? item.ios : item.android}</Text>
          </Glass>
        ))}

        {lines.length > 0 ? (
          <Glass style={styles.log} radius={theme.radius.md} intensity={20}>
            {lines.slice(-12).map((line, index) => (
              <Text key={`${index}-${line}`} style={styles.logLine}>
                {line}
              </Text>
            ))}
          </Glass>
        ) : null}
      </ScrollView>

      {/* Full screen: on iOS that takes the camera out of the window, as a pushed screen does. */}
      <Modal visible={covered} animationType="none" presentationStyle="fullScreen">
        <View style={styles.cover}>
          <Text style={styles.coverText}>Covering the camera</Text>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: theme.space(6),
    paddingBottom: theme.space(4),
  },
  title: {
    color: theme.color.text,
    fontSize: theme.font.title,
    fontWeight: '700',
  },
  close: {
    color: theme.color.accent,
    fontSize: theme.font.body,
    fontWeight: '600',
  },
  stage: {
    height: 160,
    marginHorizontal: theme.space(6),
    borderRadius: theme.radius.md,
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  stageFull: {
    flex: 1,
    height: undefined,
    marginBottom: theme.space(8),
  },
  promptPlate: {
    position: 'absolute',
    top: theme.space(4),
    left: theme.space(4),
    right: theme.space(4),
    padding: theme.space(4),
    borderRadius: theme.radius.md,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  prompt: {
    color: '#fff',
    fontSize: 30,
    fontWeight: '800',
    lineHeight: 38,
    textAlign: 'center',
  },
  hidden: {
    display: 'none',
  },
  list: {
    padding: theme.space(6),
    gap: theme.space(3),
  },
  card: {
    padding: theme.space(4),
    gap: theme.space(3),
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space(3),
  },
  cardText: {
    flex: 1,
    gap: theme.space(1),
  },
  cardTitle: {
    color: theme.color.text,
    fontSize: theme.font.body,
    fontWeight: '700',
  },
  cardBody: {
    color: theme.color.muted,
    fontSize: theme.font.label,
  },
  section: {
    color: theme.color.faint,
    fontSize: theme.font.tiny,
    textTransform: 'uppercase',
    letterSpacing: 0.8,
    paddingTop: theme.space(4),
  },
  command: {
    color: theme.color.accent,
    fontSize: 11,
    fontFamily: 'monospace',
  },
  report: {
    fontSize: theme.font.tiny,
    fontVariant: ['tabular-nums'],
  },
  log: {
    padding: theme.space(4),
    gap: theme.space(1),
  },
  logLine: {
    color: theme.color.muted,
    fontSize: theme.font.tiny,
    fontFamily: 'monospace',
  },
  finished: {
    color: theme.color.accent,
    fontSize: theme.font.label,
    fontWeight: '600',
    paddingHorizontal: theme.space(6),
    paddingTop: theme.space(3),
  },
  cover: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#000',
  },
  coverText: {
    color: '#fff',
    fontSize: theme.font.body,
  },
});
