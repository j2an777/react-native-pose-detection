import type { ValidationIssue } from '../errors';
import { PoseConfigError } from '../errors';
import { LOG_CATEGORIES, LOG_LEVELS } from '../types/logging';
import type { LogLevel } from '../types/logging';

function isLogLevel(value: unknown): value is LogLevel {
  return typeof value === 'string' && (LOG_LEVELS as readonly string[]).includes(value);
}

export function validateLogLevel(config: unknown): ValidationIssue[] {
  if (isLogLevel(config)) return [];

  if (typeof config !== 'object' || config === null || Array.isArray(config)) {
    return [
      { path: 'logLevel', message: `must be a level or a map of categories to levels` },
      { path: 'logLevel', message: `levels are: ${LOG_LEVELS.join(', ')}` },
    ];
  }

  const issues: ValidationIssue[] = [];
  for (const [category, level] of Object.entries(config)) {
    if (!(LOG_CATEGORIES as readonly string[]).includes(category)) {
      issues.push({
        path: `logLevel.${category}`,
        message: `unknown category, expected one of: ${LOG_CATEGORIES.join(', ')}`,
      });
      continue;
    }
    if (!isLogLevel(level)) {
      issues.push({
        path: `logLevel.${category}`,
        message: `must be one of: ${LOG_LEVELS.join(', ')}`,
      });
    }
  }
  return issues;
}

/** For `setLogLevel()` and, during render, the `logLevel` prop: an ignored level looks like the bug. */
export function assertValidLogLevel(config: unknown): void {
  const issues = validateLogLevel(config);
  if (issues.length > 0) throw new PoseConfigError(issues);
}
