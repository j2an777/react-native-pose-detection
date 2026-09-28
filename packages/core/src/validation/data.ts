import type { ValidationIssue } from '../errors';
import { PoseConfigError } from '../errors';
import { isAngleJointName, isJointName } from '../types/joints';

const MODES = ['off', 'throttled', 'batched', 'live'];

function describe(value: unknown): string {
  return typeof value === 'string' ? JSON.stringify(value) : String(value);
}

// Native drops a name it cannot read, and a dropped `select` joint stops every frame silently.
function checkJoints(
  { select, angles }: Record<string, unknown>,
  prefix: string,
  issues: ValidationIssue[],
): void {
  if (select !== undefined) {
    if (!Array.isArray(select)) {
      issues.push({ path: `${prefix}.select`, message: 'must be an array of joint names' });
    } else {
      select.forEach((joint: unknown, index) => {
        if (!isJointName(joint)) {
          issues.push({
            path: `${prefix}.select[${index}]`,
            message: `unknown joint ${describe(joint)}`,
          });
        }
      });
    }
  }

  if (angles !== undefined && typeof angles !== 'boolean') {
    if (!Array.isArray(angles)) {
      issues.push({
        path: `${prefix}.angles`,
        message: 'must be true, false or an array of joint names',
      });
    } else {
      angles.forEach((joint: unknown, index) => {
        if (isAngleJointName(joint)) return;
        issues.push({
          path: `${prefix}.angles[${index}]`,
          message: isJointName(joint)
            ? `${describe(joint)} has no angle, only joints where two limb segments meet do`
            : `unknown joint ${describe(joint)}`,
        });
      });
    }
  }
}

export function validateDataConfig(data: unknown): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (typeof data !== 'object' || data === null) return issues;
  const config = data as Record<string, unknown>;

  if (config['mode'] !== undefined && !MODES.includes(config['mode'] as string)) {
    issues.push({ path: 'data.mode', message: `must be one of: ${MODES.join(', ')}` });
  }
  checkJoints(config, 'data', issues);
  return issues;
}

/** `select` and `angles` on `detectOnImage` and `detectOnVideo`, held to the rules `data` is. */
export function validateFileJoints(options: unknown): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  if (typeof options === 'object' && options !== null) {
    checkJoints(options as Record<string, unknown>, 'options', issues);
  }
  return issues;
}

/** Runs during render, beside the trigger check, so a bad name fails at the call site. */
export function assertValidDataConfig(data: unknown): void {
  const issues = validateDataConfig(data);
  if (issues.length > 0) throw new PoseConfigError(issues);
}
