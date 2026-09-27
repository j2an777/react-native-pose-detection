import { requireNativeModule, requireNativeView } from 'expo';
import type { ComponentType } from 'react';

import type { NativePoseModule } from './contract';

// Lazily: requiring at import time throws, unhelpfully, in an app not yet rebuilt with the module.
let cachedModule: NativePoseModule | null = null;
let cachedView: ComponentType<Record<string, unknown>> | null = null;

export function getNativeModule(): NativePoseModule {
  cachedModule ??= requireNativeModule<NativePoseModule>('PoseDetection');
  return cachedModule;
}

export function getNativeView(): ComponentType<Record<string, unknown>> {
  cachedView ??= requireNativeView<Record<string, unknown>>('PoseDetection');
  return cachedView;
}

export type {
  NativeCameraPermission,
  NativePoseCameraView,
  NativePoseModule,
  NativeTriggerEvent,
} from './contract';
