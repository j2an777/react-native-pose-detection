import type { ValidationIssue } from '../errors';
import { PoseConfigError } from '../errors';
import { isAngleJointName, isJointName } from '../types/joints';

const MODES = ['off', 'throttled', 'batched', 'live'];

function describe(value: unknown): string {
  return typeof value === 'string' ? JSON.stringify(value) : String(value);
}

// Native drops a name it cannot read, and a dropped `select` joint stops every frame silently.
export function validateDataConfig(data: unknown): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (typeof data !== 'object' || data === null) return issues;
  const { mode, select, angles } = data as Record<string, unknown>;

  if (mode !== undefined && !MODES.includes(mode as string)) {
    issues.push({ path: 'data.mode', message: `must be one of: ${MODES.join(', ')}` });
  }

  if (select !== undefined) {
    if (!Array.isArray(select)) {
      issues.push({ path: 'data.select', message: 'must be an array of joint names' });
    } else {
      select.forEach((joint: unknown, index) => {
        if (!isJointName(joint)) {
          issues.push({
            path: `data.select[${index}]`,
            message: `unknown joint ${describe(joint)}`,
          });
        }
      });
    }
  }

  if (angles !== undefined && typeof angles !== 'boolean') {
    if (!Array.isArray(angles)) {
      issues.push({
        path: 'data.angles',
        message: 'must be true, false or an array of joint names',
      });
    } else {
      angles.forEach((joint: unknown, index) => {
        if (isAngleJointName(joint)) return;
        issues.push({
          path: `data.angles[${index}]`,
          message: isJointName(joint)
            ? `${describe(joint)} has no angle, only joints where two limb segments meet do`
            : `unknown joint ${describe(joint)}`,
        });
      });
    }
  }
  return issues;
}

/** Runs during render, beside the trigger check, so a bad name fails at the call site. */
export function assertValidDataConfig(data: unknown): void {
  const issues = validateDataConfig(data);
  if (issues.length > 0) throw new PoseConfigError(issues);
}
