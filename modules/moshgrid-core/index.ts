import { requireNativeModule } from 'expo-modules-core';

import type { NativeInfo } from './src/MoshgridCore.types';

const MoshgridCore = requireNativeModule('MoshgridCore');

export function nativeInfo(): NativeInfo {
  return MoshgridCore.nativeInfo();
}

export type { NativeInfo };
