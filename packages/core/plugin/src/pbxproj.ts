import type { XcodeProject } from 'expo/config-plugins';
import { IOSConfig } from 'expo/config-plugins';
import { writeFile } from 'node:fs/promises';
import { basename } from 'node:path';

import { MODEL_FILE_PATTERN } from './manifest';

// One top-level virtual group with paths relative to `ios/`, as expo-font and expo-asset do.
const RESOURCE_GROUP = 'Resources';

/** Every model reference, any variant. One left behind after a switch ships a second model. */
function findModelReferences(project: XcodeProject): string[] {
  const section = project.pbxFileReferenceSection() as Record<string, unknown>;
  const paths = new Set<string>();

  for (const [key, value] of Object.entries(section)) {
    if (key.endsWith('_comment') || typeof value !== 'object' || value === null) continue;

    const entry = value as { path?: string; name?: string };
    const raw = entry.path ?? entry.name;
    if (typeof raw !== 'string') continue;

    // A project written on Windows can hold backslashes; normalized, its reference is still found.
    const filePath = raw.replace(/^"|"$/g, '').replace(/\\/g, '/');
    if (MODEL_FILE_PATTERN.test(basename(filePath))) paths.add(filePath);
  }
  return [...paths];
}

/** `getFirstTarget` is index 0 with no product-type check, so it is only the fallback. */
function applicationTargetUuid(project: XcodeProject): string {
  const application = project.getTarget('com.apple.product-type.application') as
    | { uuid?: string }
    | null
    | undefined;

  return application?.uuid ?? project.getFirstTarget().uuid;
}

/** Leaves exactly one model referenced. Idempotent. */
export function syncModelReference(
  project: XcodeProject,
  projectName: string,
  fileName: string,
): { added: string; removed: string[] } {
  IOSConfig.XcodeUtils.ensureGroupRecursively(project, RESOURCE_GROUP);
  const groupKey = project.findPBXGroupKey({ name: RESOURCE_GROUP });

  // Not path.join: Xcode reads a backslash as part of the filename, so pbxproj paths use `/`.
  const filepath = [projectName, RESOURCE_GROUP, fileName].join('/');

  const targetUuid = applicationTargetUuid(project);
  const removed = findModelReferences(project).filter((stale) => stale !== filepath);
  for (const stale of removed) {
    project.removeResourceFile(stale, { target: targetUuid }, groupKey);
  }

  // No targetUuid, so config-plugins picks the application target rather than merely the first.
  IOSConfig.XcodeUtils.addResourceFileToGroup({
    filepath,
    groupName: RESOURCE_GROUP,
    project,
    isBuildFile: true,
  });

  return { added: filepath, removed };
}

export function loadProject(projectRoot: string): XcodeProject {
  return IOSConfig.XcodeUtils.getPbxproj(projectRoot);
}

export async function saveProject(project: XcodeProject): Promise<string> {
  await writeFile(project.filepath, project.writeSync());
  return project.filepath;
}

/** Asked through here so the CLI and the plugin agree in a renamed project. */
export function iosSourceRootName(projectRoot: string): string | null {
  try {
    return basename(IOSConfig.Paths.getSourceRoot(projectRoot));
  } catch {
    return null;
  }
}
