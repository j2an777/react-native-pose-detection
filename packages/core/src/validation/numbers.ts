import type { ValidationIssue } from '../errors';
import { PoseConfigError } from '../errors';
import type { ExportOptions } from '../exportPose';
import type { StaticOptions, VideoOptions } from '../staticInput';
import type { PoseCameraProps } from '../types/props';

// NaN and the infinities type-check as numbers, but Swift traps converting one to an integer.
// Ranges are left to native, which clamps them.

type CameraNumbers = Pick<
  PoseCameraProps,
  'targetFps' | 'maxPoses' | 'minConfidence' | 'smoothing' | 'data' | 'overlay'
>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function checkNumber(value: unknown, path: string, issues: ValidationIssue[]): void {
  if (value === undefined || value === null) return;
  if (typeof value !== 'number') {
    issues.push({ path, message: `must be a number, received ${typeof value}` });
    return;
  }
  if (!Number.isFinite(value)) {
    issues.push({ path, message: `must be a finite number, received ${String(value)}` });
  }
}

function checkFields(
  record: unknown,
  fields: readonly string[],
  prefix: string,
  issues: ValidationIssue[],
): void {
  if (!isRecord(record)) return;
  for (const field of fields) checkNumber(record[field], `${prefix}.${field}`, issues);
}

function checkOverlay(overlay: unknown, path: string, issues: ValidationIssue[]): void {
  if (!isRecord(overlay)) return;
  checkFields(overlay, ['lineWidth', 'pointRadius', 'minVisibility'], path, issues);
  const angles = overlay['angles'];
  if (!Array.isArray(angles)) return;
  angles.forEach((arc, index) =>
    checkFields(arc, ['radius', 'decimals', 'minVisibility'], `${path}.angles[${index}]`, issues),
  );
}

function assertNone(issues: ValidationIssue[]): void {
  if (issues.length > 0) throw new PoseConfigError(issues);
}

export function validateCameraNumbers(props: CameraNumbers): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (props.targetFps !== 'auto') checkNumber(props.targetFps, 'targetFps', issues);
  checkNumber(props.maxPoses, 'maxPoses', issues);
  checkNumber(props.minConfidence, 'minConfidence', issues);
  checkFields(props.smoothing, ['minCutoff', 'beta'], 'smoothing', issues);
  checkFields(props.data, ['throttleMs', 'flushMs'], 'data', issues);
  checkOverlay(props.overlay, 'overlay', issues);
  return issues;
}

/** Runs during render, so a bad value fails at the call site with a path. */
export function assertValidCameraNumbers(props: CameraNumbers): void {
  assertNone(validateCameraNumbers(props));
}

export function validateFileOptions(
  options: StaticOptions | VideoOptions | ExportOptions | undefined,
): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (options === undefined) return issues;
  checkFields(
    options,
    ['maxPoses', 'fps', 'startMs', 'endMs', 'minConfidence', 'maxSize', 'quality'],
    'options',
    issues,
  );
  checkOverlay((options as ExportOptions).overlay, 'options.overlay', issues);
  return issues;
}

/** Thrown synchronously, before a task exists, so a bad option never becomes a native error. */
export function assertValidFileOptions(
  options: StaticOptions | VideoOptions | ExportOptions | undefined,
): void {
  assertNone(validateFileOptions(options));
}
