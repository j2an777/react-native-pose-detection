// BlazePose's 33 landmarks in model order: the index space of the wire and both native sides.

const NAMES = [
  'nose',
  'leftEyeInner',
  'leftEye',
  'leftEyeOuter',
  'rightEyeInner',
  'rightEye',
  'rightEyeOuter',
  'leftEar',
  'rightEar',
  'mouthLeft',
  'mouthRight',
  'leftShoulder',
  'rightShoulder',
  'leftElbow',
  'rightElbow',
  'leftWrist',
  'rightWrist',
  'leftPinky',
  'rightPinky',
  'leftIndex',
  'rightIndex',
  'leftThumb',
  'rightThumb',
  'leftHip',
  'rightHip',
  'leftKnee',
  'rightKnee',
  'leftAnkle',
  'rightAnkle',
  'leftHeel',
  'rightHeel',
  'leftFootIndex',
  'rightFootIndex',
] as const;

export type JointName = (typeof NAMES)[number];

export const JOINT_NAMES: readonly JointName[] = NAMES;

/** Annotated as `33` so adding or removing a name fails the build rather than the wire format. */
export const LANDMARK_COUNT: 33 = NAMES.length;

export const JOINT_INDEX = Object.fromEntries(
  NAMES.map((name, index) => [name, index]),
) as Readonly<Record<JointName, number>>;

// Not `value in JOINT_INDEX`: `in` walks the prototype chain, so 'toString' would pass.
const JOINT_NAME_SET: ReadonlySet<string> = new Set(NAMES);

export function isJointName(value: unknown): value is JointName {
  return typeof value === 'string' && JOINT_NAME_SET.has(value);
}

const CONNECTIONS = [
  ['nose', 'leftEyeInner'],
  ['leftEyeInner', 'leftEye'],
  ['leftEye', 'leftEyeOuter'],
  ['leftEyeOuter', 'leftEar'],
  ['nose', 'rightEyeInner'],
  ['rightEyeInner', 'rightEye'],
  ['rightEye', 'rightEyeOuter'],
  ['rightEyeOuter', 'rightEar'],
  ['mouthLeft', 'mouthRight'],
  ['leftShoulder', 'rightShoulder'],
  ['leftShoulder', 'leftElbow'],
  ['leftElbow', 'leftWrist'],
  ['leftWrist', 'leftPinky'],
  ['leftWrist', 'leftIndex'],
  ['leftWrist', 'leftThumb'],
  ['leftPinky', 'leftIndex'],
  ['rightShoulder', 'rightElbow'],
  ['rightElbow', 'rightWrist'],
  ['rightWrist', 'rightPinky'],
  ['rightWrist', 'rightIndex'],
  ['rightWrist', 'rightThumb'],
  ['rightPinky', 'rightIndex'],
  ['leftShoulder', 'leftHip'],
  ['rightShoulder', 'rightHip'],
  ['leftHip', 'rightHip'],
  ['leftHip', 'leftKnee'],
  ['rightHip', 'rightKnee'],
  ['leftKnee', 'leftAnkle'],
  ['rightKnee', 'rightAnkle'],
  ['leftAnkle', 'leftHeel'],
  ['rightAnkle', 'rightHeel'],
  ['leftHeel', 'leftFootIndex'],
  ['rightHeel', 'rightFootIndex'],
  ['leftAnkle', 'leftFootIndex'],
  ['rightAnkle', 'rightFootIndex'],
] as const satisfies readonly (readonly [JointName, JointName])[];

/** The 35 bones both native overlays draw, pair for pair. */
export const POSE_CONNECTIONS: readonly (readonly [JointName, JointName])[] = CONNECTIONS;

export const CONNECTION_COUNT: 35 = CONNECTIONS.length;

/** `POSE_CONNECTIONS` as landmark indices, the form the native overlays iterate. */
export const POSE_CONNECTION_INDICES: readonly (readonly [number, number])[] = CONNECTIONS.map(
  ([from, to]) => [JOINT_INDEX[from], JOINT_INDEX[to]] as const,
);

const ANGLE_NAMES = [
  'leftShoulder',
  'rightShoulder',
  'leftElbow',
  'rightElbow',
  'leftWrist',
  'rightWrist',
  'leftHip',
  'rightHip',
  'leftKnee',
  'rightKnee',
  'leftAnkle',
  'rightAnkle',
] as const satisfies readonly JointName[];

/** The 12 joints where two limb segments meet, the only ones with an angle. */
export type AngleJointName = (typeof ANGLE_NAMES)[number];

export const ANGLE_JOINT_NAMES: readonly AngleJointName[] = ANGLE_NAMES;

/** `[proximal, vertex, distal]`: the angle is measured at the vertex. */
export const ANGLE_JOINTS = {
  leftShoulder: ['leftHip', 'leftShoulder', 'leftElbow'],
  rightShoulder: ['rightHip', 'rightShoulder', 'rightElbow'],
  leftElbow: ['leftShoulder', 'leftElbow', 'leftWrist'],
  rightElbow: ['rightShoulder', 'rightElbow', 'rightWrist'],
  leftWrist: ['leftElbow', 'leftWrist', 'leftIndex'],
  rightWrist: ['rightElbow', 'rightWrist', 'rightIndex'],
  leftHip: ['leftShoulder', 'leftHip', 'leftKnee'],
  rightHip: ['rightShoulder', 'rightHip', 'rightKnee'],
  leftKnee: ['leftHip', 'leftKnee', 'leftAnkle'],
  rightKnee: ['rightHip', 'rightKnee', 'rightAnkle'],
  leftAnkle: ['leftKnee', 'leftAnkle', 'leftFootIndex'],
  rightAnkle: ['rightKnee', 'rightAnkle', 'rightFootIndex'],
} as const satisfies Readonly<Record<AngleJointName, readonly [JointName, JointName, JointName]>>;

const ANGLE_JOINT_NAME_SET: ReadonlySet<string> = new Set(ANGLE_NAMES);

export function isAngleJointName(value: unknown): value is AngleJointName {
  return typeof value === 'string' && ANGLE_JOINT_NAME_SET.has(value);
}
