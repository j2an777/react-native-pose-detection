import { Platform } from 'react-native';

export const theme = {
  color: {
    background: '#F4F6F8',
    surface: '#FFFFFF',
    surfaceSunken: '#E9EDF1',
    border: '#DDE2E8',
    borderStrong: '#C6CDD6',

    text: '#0B1220',
    muted: '#5A6472',
    faint: '#8B95A3',

    accent: '#0B7C93',
    accentSoft: '#DBF1F6',
    /** The skeleton's color: vivid, because it is drawn over video. */
    overlay: '#00E5FF',

    danger: '#D93A4B',
    dangerSoft: '#FDECEE',
    good: '#12925A',

    /** Behind glass over the camera, so a control stays readable on a bright frame. */
    scrim: 'rgba(255,255,255,0.72)',
    /** Android's glass, which has no blur and carries the contrast alone. */
    scrimSolid: 'rgba(255,255,255,0.94)',
  },

  space: (steps: number) => steps * 4,

  radius: {
    sm: 12,
    md: 18,
    lg: 26,
    pill: 999,
  },

  font: {
    display: 32,
    title: 19,
    body: 15,
    label: 13,
    tiny: 11,
  },

  /** Tuned per platform: Android's `elevation` at an iOS shadow's strength reads as a dark halo. */
  lift: Platform.select({
    ios: {
      shadowColor: '#0B1220',
      shadowOpacity: 0.1,
      shadowRadius: 20,
      shadowOffset: { width: 0, height: 6 },
    },
    default: { elevation: 2 },
  }),

  /** For the one element that floats over everything, tuned per platform as `lift` is. */
  liftStrong: Platform.select({
    ios: {
      shadowColor: '#0B1220',
      shadowOpacity: 0.18,
      shadowRadius: 28,
      shadowOffset: { width: 0, height: 12 },
    },
    default: { elevation: 3 },
  }),
} as const;
