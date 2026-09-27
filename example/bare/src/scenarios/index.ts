import { PERSON } from './person';
import { SCENARIOS as UNATTENDED } from './runners';

export const SCENARIOS = [...UNATTENDED, PERSON];
export { EXTERNAL } from './runners';
export type { CameraProps, ScenarioReport } from './types';
