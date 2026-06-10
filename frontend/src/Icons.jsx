/** Slack-style icons for the export UI */

/** Official Slack logo - symbol only (cyan top-left, green top-right, red bottom-left, yellow bottom-right) */
export function SlackLogo({ size = 36, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 36 36" fill="none" preserveAspectRatio="xMidYMid meet" className={className} aria-hidden style={{ display: 'block' }}>
      <path fill="#36C5F0" d="M9.5 0a3.5 3.5 0 0 0 0 7h3.5V7a3.5 3.5 0 0 0-3.5-7Z" />
      <path fill="#2EB67D" d="M12.5 7V3.5a3.5 3.5 0 0 1 7 0V7h-7Z" />
      <path fill="#ECB22E" d="M19.5 7v3.5H23a3.5 3.5 0 0 0 0-7h-3.5Z" />
      <path fill="#E01E5A" d="M23 12.5h-3.5V16a3.5 3.5 0 0 0 7 0v-3.5H23Z" />
      <path fill="#36C5F0" d="M19.5 16v3.5H23a3.5 3.5 0 0 1 0 7h-3.5V16Z" />
      <path fill="#2EB67D" d="M16 19.5h-3.5V23a3.5 3.5 0 0 0 7 0v-3.5H16Z" />
      <path fill="#ECB22E" d="M12.5 23v3.5H9a3.5 3.5 0 0 1 0-7h3.5Z" />
      <path fill="#E01E5A" d="M9 12.5h3.5V9a3.5 3.5 0 0 0-7 0v3.5H9Z" />
    </svg>
  )
}

export function DmIcon({ size = 20, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
    </svg>
  )
}

/** All DMs - inbox icon (all messages) */
export function DmAllIcon({ size = 20, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
      <path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
    </svg>
  )
}

export function PrivateChannelIcon({ size = 20, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  )
}

/** All Private Channels - lock with horizontal bar (all channels) */
export function PrivateChannelAllIcon({ size = 20, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      <line x1="6" y1="15" x2="18" y2="15" />
    </svg>
  )
}

export function ExportIcon({ size = 18, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  )
}
