import { getNativeModule } from '../native';
import type { NativeCameraPermission } from '../native';

/** `denied` can be asked again; `blocked` cannot, only the app's Settings page can grant it. */
export type CameraPermissionStatus = 'granted' | 'denied' | 'blocked' | 'undetermined';

export type CameraPermission = {
  readonly status: CameraPermissionStatus;
  readonly granted: boolean;
  /**
   * False once the system will not prompt, `granted` included. If not granted, send the user to
   * `Linking.openSettings()`.
   */
  readonly canAskAgain: boolean;
};

function toPermission(native: NativeCameraPermission): CameraPermission {
  const { canAskAgain } = native;

  if (native.status === 'granted') return { status: 'granted', granted: true, canAskAgain: false };
  if (native.status === 'undetermined') {
    return { status: 'undetermined', granted: false, canAskAgain: true };
  }
  return { status: canAskAgain ? 'denied' : 'blocked', granted: false, canAskAgain };
}

/** Reads the current status. Never prompts. */
export async function getCameraPermission(): Promise<CameraPermission> {
  return toPermission(await getNativeModule().getCameraPermission());
}

/** Prompts if the system still will; resolves at once, no dialog, when `granted` or `blocked`. */
export async function requestCameraPermission(): Promise<CameraPermission> {
  return toPermission(await getNativeModule().requestCameraPermission());
}
