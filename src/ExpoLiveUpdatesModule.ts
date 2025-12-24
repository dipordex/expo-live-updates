import { NativeModule, requireOptionalNativeModule } from 'expo'
import type { EventSubscription } from 'react-native'

import type {
  ActionStateEvent,
  LiveUpdateConfig,
  LiveUpdateState,
  NotificationStateChangeEvent,
  TokenChangeEvent,
} from './types'

type ExpoLiveUpdatesModuleEvents = {
  onNotificationStateChange: (event: NotificationStateChangeEvent) => void
  onTokenChange: (event: TokenChangeEvent) => void
  onButtonPressed: (event: ActionStateEvent) => void
}
declare class ExpoLiveUpdatesModule extends NativeModule<ExpoLiveUpdatesModuleEvents> {
  startLiveUpdate: (state: LiveUpdateState, config?: LiveUpdateConfig) => number
  stopLiveUpdate: (notificationId: number) => void
  updateLiveUpdate: (
    notificationId: number,
    state: LiveUpdateState,
    config?: LiveUpdateConfig,
  ) => void
  addNotificationStateChangeListener: () => EventSubscription | undefined
  addTokenChangeListener: () => EventSubscription | undefined
  addLiveUpdateActionListener: () => EventSubscription | undefined
}

const module = requireOptionalNativeModule<ExpoLiveUpdatesModule>(
  'ExpoLiveUpdatesModule',
)

export default module
