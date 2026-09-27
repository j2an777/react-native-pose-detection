import { Ionicons } from '@expo/vector-icons';
import * as React from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { theme } from '../theme';
import type { IconName } from './Controls';

export type TabId = 'home' | 'live' | 'upload';

const TABS: { id: TabId; label: string; icon: IconName; active: IconName }[] = [
  { id: 'home', label: 'Overview', icon: 'apps-outline', active: 'apps' },
  { id: 'live', label: 'Capture', icon: 'camera-outline', active: 'camera' },
  { id: 'upload', label: 'Studio', icon: 'color-wand-outline', active: 'color-wand' },
];

export function NavBar({ active, onSelect }: { active: TabId; onSelect: (tab: TabId) => void }) {
  const insets = useSafeAreaInsets();
  return (
    <View
      // Android's gesture bar inset alone leaves a floating bar too low, so it gets 16 on top.
      style={[styles.wrap, { bottom: insets.bottom + Platform.select({ ios: 0, default: 16 }) }]}
      pointerEvents="box-none"
    >
      <View style={styles.bar}>
        {TABS.map((tab) => {
          const selected = tab.id === active;
          return (
            <Pressable
              key={tab.id}
              onPress={() => onSelect(tab.id)}
              accessibilityRole="tab"
              accessibilityState={{ selected }}
              accessibilityLabel={tab.label}
              style={({ pressed }) => [
                styles.tab,
                selected && styles.tabSelected,
                pressed && styles.pressed,
              ]}
            >
              <Ionicons
                name={selected ? tab.active : tab.icon}
                size={19}
                color={selected ? theme.color.accent : theme.color.muted}
              />
              {selected ? <Text style={styles.label}>{tab.label}</Text> : null}
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

/** What a scrolling screen leaves free at the bottom so the bar never covers its last row. */
export const NAV_CLEARANCE = 120;

const styles = StyleSheet.create({
  wrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space(1),
    padding: theme.space(1.5),
    borderRadius: theme.radius.pill,
    backgroundColor: theme.color.background,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.color.border,
    ...theme.liftStrong,
  },
  tab: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space(2),
    height: 44,
    paddingHorizontal: theme.space(4.5),
    borderRadius: theme.radius.pill,
  },
  tabSelected: {
    backgroundColor: theme.color.accentSoft,
  },
  pressed: {
    opacity: 0.55,
  },
  label: {
    color: theme.color.accent,
    fontSize: theme.font.label,
    fontWeight: '600',
    letterSpacing: -0.1,
  },
});
