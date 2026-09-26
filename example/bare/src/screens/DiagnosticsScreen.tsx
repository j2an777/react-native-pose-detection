import { File, Paths } from 'expo-file-system';
import * as React from 'react';
import { Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  PoseCamera,
  type CameraChangeEvent,
  type PoseCameraRef,
  type ReadyEvent,
} from 'react-native-pose-detection';

import { Button } from '../components/Controls';
import { Glass } from '../components/Glass';
import type { DiagnosticsRequest } from '../diagnosticsRequest';
import { EXTERNAL, SCENARIOS, type ScenarioReport } from '../scenarios';
import { theme } from '../theme';

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Printed before every line an automated run logs, so a device log can be filtered to them. */
const LOG_TAG = 'POSE_DIAG';

/**
 * The device regression harness.
 *
 * Reached from a quiet link on the overview rather than the tab bar: it exists so the crashes in
 * docs/testing.md can be reproduced on a real phone, and it is not part of what this app is for.
 * The camera it drives is deliberately small; the scenarios care about lifecycle, not about what
 * the preview looks like.
 *
 * A launch can ask for a sweep, see `diagnosticsRequest`: the scenarios then run on their own, one
 * after another, and the reports land in `diagnostics.json` in the app's documents directory, where
 * `xcrun devicectl` or `adb` can collect them.
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
  const [variant, setVariant] = React.useState(false);
  const [finished, setFinished] = React.useState<string | null>(null);
  const ready = React.useRef<(() => void) | null>(null);
  const readyCount = React.useRef(0);
  const facing = React.useRef<'front' | 'back' | null>(null);
  const cameraChanges = React.useRef(0);
  const insets = useSafeAreaInsets();

  const onReady = React.useCallback((event: ReadyEvent) => {
    readyCount.current += 1;
    facing.current = event.facing;
    ready.current?.();
    ready.current = null;
  }, []);

  const onCameraChange = React.useCallback((event: CameraChangeEvent) => {
    cameraChanges.current += 1;
    facing.current = event.facing;
  }, []);

  const remount = React.useCallback(
    () =>
      new Promise<void>((resolve) => {
        ready.current = resolve;
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
      try {
        const report = await scenario.run({
          camera,
          remount,
          remountNow,
          readyCount: () => readyCount.current,
          facing: () => facing.current,
          cameraChanges: () => cameraChanges.current,
          cover,
          toggleProps: () => setVariant((value) => !value),
          media: autoRun?.media ?? {},
          log,
        });
        setReports((value) => ({ ...value, [id]: report }));
        return report;
      } catch (problem) {
        log(`${id} threw: ${String(problem)}`);
        return null;
      } finally {
        setRunning(null);
      }
    },
    [autoRun, cover, log, remount, remountNow],
  );

  React.useEffect(() => {
    if (!autoRun) return;
    let cancelled = false;
    void (async () => {
      // Let the first mount come up before anything is timed against it.
      await sleep(2_500);
      const ids =
        autoRun.scenarios === 'all'
          ? SCENARIOS.filter((item) => !item.slow).map((item) => item.id)
          : autoRun.scenarios.filter((id) => SCENARIOS.some((item) => item.id === id));
      const collected: ScenarioReport[] = [];
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

      <View style={styles.stage}>
        <PoseCamera
          key={generation}
          ref={camera}
          style={StyleSheet.absoluteFill}
          // Flipped by the prop-toggle scenario: every one of these must be applied in place.
          overlay={{ color: theme.color.accent, angles: variant ? [{ joint: 'leftKnee' }] : [] }}
          smoothing={variant ? { minCutoff: 0.05, beta: 80 } : 'auto'}
          data={{ mode: variant ? 'throttled' : 'off' }}
          onPose={variant ? () => undefined : undefined}
          onReady={onReady}
          onCameraChange={onCameraChange}
        />
      </View>

      {finished ? <Text style={styles.finished}>Automated run finished: {finished}</Text> : null}

      <ScrollView contentContainerStyle={styles.list} showsVerticalScrollIndicator={false}>
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

      {/* Full screen, so on iOS it takes the camera's view out of the window, as a pushed screen does. */}
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
