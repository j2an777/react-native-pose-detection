import * as React from 'react';
import { Animated, Easing, StyleSheet } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';

import { Glass } from './Glass';
import { theme } from '../theme';

/**
 * Slides in and out, since a panel that pops in over live video reads as a glitch. Native driver,
 * so pose callbacks on the JS thread cannot make it stutter.
 */
export function Sheet({
  visible,
  children,
  style,
  radius = theme.radius.lg,
}: {
  visible: boolean;
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  radius?: number;
}) {
  const progress = React.useRef(new Animated.Value(visible ? 1 : 0)).current;
  const [mounted, setMounted] = React.useState(visible);

  React.useEffect(() => {
    if (visible) setMounted(true);
    Animated.timing(progress, {
      toValue: visible ? 1 : 0,
      duration: visible ? 220 : 160,
      easing: visible ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished && !visible) setMounted(false);
    });
  }, [visible, progress]);

  if (!mounted) return null;

  return (
    <Animated.View
      style={{
        opacity: progress,
        transform: [
          { translateY: progress.interpolate({ inputRange: [0, 1], outputRange: [24, 0] }) },
        ],
      }}
    >
      <Glass style={[styles.sheet, style]} radius={radius} intensity={60}>
        {children}
      </Glass>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  sheet: {
    overflow: 'hidden',
  },
});
