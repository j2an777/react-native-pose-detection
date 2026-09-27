import { BlurView } from 'expo-blur';
import * as React from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';

import { theme } from '../theme';

type Props = {
  children?: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  radius?: number;
  intensity?: number;
};

/**
 * A panel over the camera: blur for depth, a near-white scrim for contrast. No blur on Android,
 * where expo-blur needs a `blurTarget` view and cannot sample a camera preview.
 */
export function Glass({ children, style, radius = theme.radius.lg, intensity = 40 }: Props) {
  if (Platform.OS !== 'ios') {
    return (
      <View style={[styles.blur, styles.solid, { borderRadius: radius }, style]}>{children}</View>
    );
  }
  return (
    <BlurView
      intensity={intensity}
      tint="light"
      style={[styles.blur, { borderRadius: radius }, style]}
    >
      {children}
    </BlurView>
  );
}

/** The same shape without the blur, for panels that sit on the page rather than over the camera. */
export function Card({ children, style, radius = theme.radius.md }: Props) {
  return <View style={[styles.card, { borderRadius: radius }, style]}>{children}</View>;
}

const styles = StyleSheet.create({
  blur: {
    backgroundColor: theme.color.scrim,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(12,13,15,0.10)',
    overflow: 'hidden',
    ...theme.lift,
  },
  solid: {
    backgroundColor: theme.color.scrimSolid,
  },
  card: {
    backgroundColor: theme.color.surface,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.color.border,
  },
});
