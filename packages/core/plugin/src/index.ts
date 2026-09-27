import type { ConfigPlugin } from 'expo/config-plugins';
import { createRunOncePlugin } from 'expo/config-plugins';

import type { PoseDetectionPluginOptions } from './options';
import { resolveOptions } from './options';
import { withAndroidModel } from './withAndroidModel';
import { withIosModel } from './withIosModel';

const PACKAGE_NAME = 'react-native-pose-detection';

const withPoseDetection: ConfigPlugin<PoseDetectionPluginOptions | undefined> = (
  config,
  options,
) => {
  const resolved = resolveOptions(options);

  config = withAndroidModel(config, resolved);
  config = withIosModel(config, resolved);

  return config;
};

// Listed twice, directly and via another package, it must not race two downloads to one path.
export default createRunOncePlugin(withPoseDetection, PACKAGE_NAME);

export type { PoseDetectionPluginOptions };
