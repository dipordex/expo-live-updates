export type LiveUpdateImage = {
  url: string
  isRemote: boolean
}

export type LiveUpdateProgressPoint = {
  position: number
  color?: string
}

export type LiveUpdateProgressSegment = {
  length: number
  color?: string
}

type StopWatch = {
  id: string;
  startedAt: number | null;
  accumulated: number;
  isRunning: boolean;
  lapCount: number;
}

type Timer = {
  id?: string;
  duration?: number;
  remaining?: number;
  isRunning?: boolean;
  endsAt?: number;
  startTime?: number | null;
}

export type LiveUpdateProgress = {
  max?: number
  progress?: number
  indeterminate?: boolean
  points?: LiveUpdateProgressPoint[]
  segments?: LiveUpdateProgressSegment[]
}

export type LiveUpdateState = {
  title: string
  subtitle?: string
  mode?: string
  stopwatch?: StopWatch
  timer?: Timer
  showInDynamicIsland?: boolean
}

export type LiveUpdateConfig = {
  deepLinkUrl?: string
  iconBackgroundColor?: string // only SDK < 16
}

export type TokenChangeEvent = {
  token: string
}

export type NotificationStateChangeEvent = {
  notificationId: number
  action: 'dismissed' | 'updated' | 'started' | 'stopped' | 'clicked'
  timestamp: number
}

export type NotificationStateChangeListener = (
  event: NotificationStateChangeEvent,
) => void
