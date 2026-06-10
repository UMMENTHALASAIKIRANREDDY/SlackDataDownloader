import { useState, useCallback, useEffect } from 'react'
import axios from 'axios'
import { DmIcon, DmAllIcon, PrivateChannelIcon, PrivateChannelAllIcon } from './Icons'
import './App.css'

// API base URL is env-driven (see .env / .env.example).
// Empty (default in production) => relative /api calls served same-origin via reverse proxy.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''
if (API_BASE_URL) {
  axios.defaults.baseURL = API_BASE_URL
}

const API_URL = '/api/export/slack-dm-mpim'
const API_URL_ALL_DMS = '/api/export/all-dms-dual'
const API_URL_ALL_DMS_COUNT = '/api/export/all-dms-count'
const API_URL_LATEST_DOWNLOAD = '/api/export/latest-download'
const API_URL_EXPORT_START = '/api/export/start'
const EXPORT_JOB_STORAGE_KEY = 'slack_export_job'

const TAB = { DM_MANUAL: 'dmManual', ALL_DMS: 'allDms', PRIVATE_CHANNEL_MANUAL: 'privateChannelManual', ALL_PRIVATE_CHANNELS: 'allPrivateChannels' }

const PAGE_PRIVATE = {
  CHANNEL_DETAILS: 1,
  DATE_DOWNLOAD: 2,
}

function parseIds(value) {
  if (!value || typeof value !== 'string') return []
  return value.split(',').map((id) => id.trim()).filter(Boolean)
}

function App() {
  const [activeTab, setActiveTab] = useState(TAB.DM_MANUAL)
  const [dmChannelIdsText, setDmChannelIdsText] = useState('')
  const [mpimChannelIdsText, setMpimChannelIdsText] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [loading, setLoading] = useState(false)
  const [downloadProgress, setDownloadProgress] = useState(null)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const [allDmsExportMode, setAllDmsExportMode] = useState('FULL')
  const [allDmsFromDate, setAllDmsFromDate] = useState('')
  const [allDmsToDate, setAllDmsToDate] = useState('')
  const [allDmsLoading, setAllDmsLoading] = useState(false)
  const [allDmsDownloadProgress, setAllDmsDownloadProgress] = useState(null)
  const [allDmsError, setAllDmsError] = useState('')
  const [allDmsSuccess, setAllDmsSuccess] = useState('')
  const [allDmsCountLoading, setAllDmsCountLoading] = useState(false)
  const [allDmsCountError, setAllDmsCountError] = useState('')
  const [allDmsCountResult, setAllDmsCountResult] = useState(null)

  const [privateChannelsMode, setPrivateChannelsMode] = useState('ALL') // used only for ALL_PRIVATE_CHANNELS tab
  const [privateChannelsPage, setPrivateChannelsPage] = useState(PAGE_PRIVATE.CHANNEL_DETAILS)
  const [privateChannelIdsText, setPrivateChannelIdsText] = useState('')
  const [privateChannelsExportMode, setPrivateChannelsExportMode] = useState('FULL')
  const [privateChannelsFromDate, setPrivateChannelsFromDate] = useState('')
  const [privateChannelsToDate, setPrivateChannelsToDate] = useState('')
  const [privateChannelsLoading, setPrivateChannelsLoading] = useState(false)
  const [privateChannelsError, setPrivateChannelsError] = useState('')
  const [privateChannelsSuccess, setPrivateChannelsSuccess] = useState('')

  // Async export job: start job → poll status → show download link (persisted in localStorage)
  const [exportJobId, setExportJobId] = useState(() => {
    try {
      const raw = localStorage.getItem(EXPORT_JOB_STORAGE_KEY)
      if (!raw) return null
      const data = JSON.parse(raw)
      return data?.jobId ?? null
    } catch (_) { return null }
  })
  const [exportJobExportType, setExportJobExportType] = useState(() => {
    try {
      const raw = localStorage.getItem(EXPORT_JOB_STORAGE_KEY)
      if (!raw) return null
      const data = JSON.parse(raw)
      return data?.exportType ?? null
    } catch (_) { return null }
  })
  const [exportJobStatus, setExportJobStatus] = useState(null)
  const [exportJobDownloadUrl, setExportJobDownloadUrl] = useState(null)
  const [exportJobSuggestedFilename, setExportJobSuggestedFilename] = useState(null)
  const [exportJobError, setExportJobError] = useState(null)

  const exportJobRunning = exportJobId && exportJobStatus !== 'COMPLETED' && exportJobStatus !== 'FAILED'
  const privateChannelsJobRunning = (exportJobExportType === 'private-channels' || exportJobExportType === 'private-channels-manual') && exportJobRunning
  const privateChannelsManualFlow = activeTab === TAB.PRIVATE_CHANNEL_MANUAL
  const isDownloadInProgress = loading || allDmsLoading || privateChannelsLoading || exportJobRunning

  useEffect(() => {
    if (!isDownloadInProgress) return
    const handleBeforeUnload = (e) => {
      e.preventDefault()
      e.returnValue = 'Export is in progress. You can close this page and come back later to download from the link.'
      return e.returnValue
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [isDownloadInProgress])

  // Poll job status when we have a job that is not yet completed/failed (or status unknown after refresh)
  useEffect(() => {
    if (!exportJobId) return
    if (exportJobStatus === 'COMPLETED' || exportJobStatus === 'FAILED') return
    const fetchStatus = async () => {
      try {
        const res = await axios.get(`/api/export/status/${exportJobId}`, { timeout: 10000 })
        const d = res.data
        setExportJobStatus(d.status)
        setExportJobDownloadUrl(d.downloadUrl ?? null)
        setExportJobSuggestedFilename(d.suggestedFilename ?? null)
        setExportJobError(d.errorMessage ?? null)
      } catch (_) {
        // Server stopped or unreachable — stop "Preparing…" and clear persisted job
        setExportJobStatus('FAILED')
        setExportJobError('Server stopped or unavailable. Export was interrupted.')
        try {
          localStorage.removeItem(EXPORT_JOB_STORAGE_KEY)
        } catch (_) {}
      }
    }
    fetchStatus()
    const interval = setInterval(fetchStatus, 5000)
    return () => clearInterval(interval)
  }, [exportJobId, exportJobStatus])

  const triggerZipDownload = useCallback((blob, filename) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename || 'dms-export.zip'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }, [])

  const setExportJob = useCallback((jobId, exportType) => {
    setExportJobId(jobId)
    setExportJobExportType(exportType)
    setExportJobStatus('PENDING')
    setExportJobDownloadUrl(null)
    setExportJobSuggestedFilename(null)
    setExportJobError(null)
    try {
      localStorage.setItem(EXPORT_JOB_STORAGE_KEY, JSON.stringify({ jobId, exportType }))
    } catch (_) {}
  }, [])

  const clearExportJob = useCallback(() => {
    setExportJobId(null)
    setExportJobExportType(null)
    setExportJobStatus(null)
    setExportJobDownloadUrl(null)
    setExportJobSuggestedFilename(null)
    setExportJobError(null)
    try {
      localStorage.removeItem(EXPORT_JOB_STORAGE_KEY)
    } catch (_) {}
  }, [])

  const handleDownloadFromJob = useCallback(async () => {
    if (!exportJobDownloadUrl) return
    try {
      const base = axios.defaults.baseURL || ''
      const url = (base + exportJobDownloadUrl).replace(/([^:]\/)\/+/g, '$1')
      const res = await axios.get(url, { responseType: 'blob', timeout: 300000 })
      const blob = res.data
      if (blob && blob.size > 0) {
        const filename = exportJobSuggestedFilename || `slack-export-${exportJobId}.zip`
        triggerZipDownload(blob, filename)
        setSuccess('Download started.')
        setAllDmsSuccess('Download started.')
      }
    } catch (err) {
      const msg = err?.message || 'Download failed.'
      setError(msg)
      setAllDmsError(msg)
    }
  }, [exportJobDownloadUrl, exportJobId, exportJobSuggestedFilename, triggerZipDownload])

  const collectManualPayload = useCallback(() => {
    const dmChannelIds = parseIds(dmChannelIdsText)
    const mpimChannelIds = parseIds(mpimChannelIdsText)
    return { dmChannelIds, mpimChannelIds }
  }, [dmChannelIdsText, mpimChannelIdsText])

  const handleDownload = useCallback(async () => {
    setError('')
    setSuccess('')
    const from = fromDate.trim()
    const to = toDate.trim()
    if (!from || !to) {
      setError('From Date and To Date are required.')
      return
    }
    if (new Date(to) < new Date(from)) {
      setError('To Date must be on or after From Date.')
      return
    }
    const { dmChannelIds, mpimChannelIds } = collectManualPayload()
    if (dmChannelIds.length === 0 && mpimChannelIds.length === 0) {
      setError('At least one one-on-one DM or Group DM channel ID is required.')
      return
    }

    setLoading(true)
    try {
      const manualPayload = { dmChannelIds, mpimChannelIds, fromDate: from, toDate: to }
      const response = await axios.post(API_URL_EXPORT_START, {
        exportType: 'manual',
        manualPayload,
      }, { timeout: 30000 })
      const jobId = response.data?.jobId
      if (jobId) {
        setExportJob(jobId, 'manual')
        setSuccess('Export started. You can minimize this page and come back later—your download link will appear when ready.')
      } else {
        setError('Server did not return a job ID.')
      }
    } catch (err) {
      const msg = axios.isAxiosError(err) && err.response?.data != null
        ? (typeof err.response.data === 'string' ? err.response.data : err.response.data?.message || err.message)
        : (err?.message || 'Request failed.')
      setError(msg)
    } finally {
      setLoading(false)
    }
  }, [fromDate, toDate, collectManualPayload, setExportJob])

  const handleAllDmsDownload = useCallback(async () => {
    setAllDmsError('')
    setAllDmsSuccess('')
    const mode = allDmsExportMode === 'CUSTOM' ? 'CUSTOM' : 'FULL'
    if (mode === 'CUSTOM') {
      const from = allDmsFromDate.trim()
      const to = allDmsToDate.trim()
      if (!from || !to) {
        setAllDmsError('From Date and To Date are required for custom date range.')
        return
      }
      if (new Date(to) < new Date(from)) {
        setAllDmsError('To Date must be on or after From Date.')
        return
      }
    }

    setAllDmsLoading(true)
    try {
      const allDmsPayload = {
        exportMode: mode,
        mode: mode === 'FULL' ? 'ALL_HISTORY' : 'DATE_RANGE',
        ...(mode === 'CUSTOM' ? { fromDate: allDmsFromDate.trim(), toDate: allDmsToDate.trim() } : {}),
      }
      const response = await axios.post(API_URL_EXPORT_START, {
        exportType: 'all-dms',
        allDmsPayload,
      }, { timeout: 30000 })
      const jobId = response.data?.jobId
      if (jobId) {
        setExportJob(jobId, 'all-dms')
        setAllDmsSuccess('Export started. You can minimize this page and come back later—your download link will appear when ready.')
      } else {
        setAllDmsError('Server did not return a job ID.')
      }
    } catch (err) {
      const msg = axios.isAxiosError(err) && err.response?.data != null
        ? (typeof err.response.data === 'string' ? err.response.data : err.response.data?.message || err.message)
        : (err?.message || 'Request failed.')
      setAllDmsError(msg)
    } finally {
      setAllDmsLoading(false)
    }
  }, [allDmsExportMode, allDmsFromDate, allDmsToDate, setExportJob])

  const handlePrivateChannelsDownload = useCallback(async () => {
    setPrivateChannelsError('')
    setPrivateChannelsSuccess('')
    const mode = privateChannelsExportMode === 'CUSTOM' ? 'CUSTOM' : 'FULL'
    if (mode === 'CUSTOM') {
      const from = privateChannelsFromDate.trim()
      const to = privateChannelsToDate.trim()
      if (!from || !to) {
        setPrivateChannelsError('From Date and To Date are required for custom date range.')
        return
      }
      if (new Date(to) < new Date(from)) {
        setPrivateChannelsError('To Date must be on or after From Date.')
        return
      }
    }

    setPrivateChannelsLoading(true)
    try {
      const privateChannelsPayload = {
        exportMode: mode,
        mode: mode === 'FULL' ? 'ALL_HISTORY' : 'DATE_RANGE',
        ...(mode === 'CUSTOM' ? { fromDate: privateChannelsFromDate.trim(), toDate: privateChannelsToDate.trim() } : {}),
      }
      const response = await axios.post(API_URL_EXPORT_START, {
        exportType: 'private-channels',
        privateChannelsPayload,
      }, { timeout: 30000 })
      const jobId = response.data?.jobId
      if (jobId) {
        setExportJob(jobId, 'private-channels')
        setPrivateChannelsSuccess('Export started. You can close this page and come back later—your download link will appear when ready.')
      } else {
        setPrivateChannelsError('Server did not return a job ID.')
      }
    } catch (err) {
      const msg = axios.isAxiosError(err) && err.response?.data != null
        ? (typeof err.response.data === 'string' ? err.response.data : err.response.data?.message || err.message)
        : (err?.message || 'Request failed.')
      setPrivateChannelsError(msg)
    } finally {
      setPrivateChannelsLoading(false)
    }
  }, [privateChannelsExportMode, privateChannelsFromDate, privateChannelsToDate, setExportJob])

  const goBackPrivateChannels = useCallback(() => {
    setPrivateChannelsError('')
    if (privateChannelsPage === PAGE_PRIVATE.DATE_DOWNLOAD) setPrivateChannelsPage(PAGE_PRIVATE.CHANNEL_DETAILS)
  }, [privateChannelsPage])

  const handlePrivateChannelsDetailsNext = useCallback(() => {
    setPrivateChannelsError('')
    const channelIds = parseIds(privateChannelIdsText)
    if (channelIds.length === 0) {
      setPrivateChannelsError('At least one private channel ID is required.')
      return
    }
    setPrivateChannelsPage(PAGE_PRIVATE.DATE_DOWNLOAD)
  }, [privateChannelIdsText])

  const handlePrivateChannelsManualDownload = useCallback(async () => {
    setPrivateChannelsError('')
    setPrivateChannelsSuccess('')
    const channelIds = parseIds(privateChannelIdsText)
    if (channelIds.length === 0) {
      setPrivateChannelsError('At least one private channel ID is required.')
      return
    }
    const from = privateChannelsFromDate.trim()
    const to = privateChannelsToDate.trim()
    if (!from || !to) {
      setPrivateChannelsError('From Date and To Date are required.')
      return
    }
    if (new Date(to) < new Date(from)) {
      setPrivateChannelsError('To Date must be on or after From Date.')
      return
    }

    setPrivateChannelsLoading(true)
    try {
      const privateChannelsManualPayload = { channelIds, fromDate: from, toDate: to }
      const response = await axios.post(API_URL_EXPORT_START, {
        exportType: 'private-channels-manual',
        privateChannelsManualPayload,
      }, { timeout: 30000 })
      const jobId = response.data?.jobId
      if (jobId) {
        setExportJob(jobId, 'private-channels-manual')
        setPrivateChannelsSuccess('Export started. You can close this page and come back later—your download link will appear when ready.')
      } else {
        setPrivateChannelsError('Server did not return a job ID.')
      }
    } catch (err) {
      const msg = axios.isAxiosError(err) && err.response?.data != null
        ? (typeof err.response.data === 'string' ? err.response.data : err.response.data?.message || err.message)
        : (err?.message || 'Request failed.')
      setPrivateChannelsError(msg)
    } finally {
      setPrivateChannelsLoading(false)
    }
  }, [privateChannelIdsText, privateChannelsFromDate, privateChannelsToDate, setExportJob])

  const handleDownloadLastExport = useCallback(async () => {
    setAllDmsError('')
    setAllDmsSuccess('')
    try {
      const response = await axios.get(API_URL_LATEST_DOWNLOAD, { responseType: 'blob', timeout: 60000 })
      const blob = response.data
      if (blob && blob.size > 0) {
        triggerZipDownload(blob, 'Dm-export.zip')
        setAllDmsSuccess('Download started.')
      } else {
        setAllDmsError('No export file saved yet. Start an export with "Download ZIP" above; when it finishes, use the "Download ZIP" button that appears.')
      }
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.status === 404) {
        setAllDmsError('No export file saved yet. Start an export with "Download ZIP" above; when it finishes, use the "Download ZIP" button that appears. This button works after an export has completed.')
      } else {
        setAllDmsError(err?.message || 'Could not download last export.')
      }
    }
  }, [triggerZipDownload])

  const handleCheckDmCount = useCallback(async () => {
    setAllDmsCountError('')
    setAllDmsCountResult(null)
    setAllDmsCountLoading(true)
    try {
      const response = await axios.get(API_URL_ALL_DMS_COUNT, { timeout: 60000 })
      setAllDmsCountResult(response.data)
    } catch (err) {
      const msg = axios.isAxiosError(err) && err.response?.data != null
        ? (typeof err.response.data === 'string' ? err.response.data : err.response.data?.message || err.message)
        : (err?.message || 'Request failed.')
      setAllDmsCountError(msg)
    } finally {
      setAllDmsCountLoading(false)
    }
  }, [])

  const goBack = useCallback(() => setError(''), [])

  return (
    <div className="app">
      {isDownloadInProgress && (
        <div className="download-guard-banner" role="alert">
          Do not close or refresh this window until the download completes. Leaving now will cancel the export.
        </div>
      )}
      <header className="app-header">
        <div className="app-header-inner">
          <div className="app-brand">
            <div className="app-logo-wrap">
              <img src="/slack-logo.png" alt="Slack" className="app-logo" width={44} height={44} />
            </div>
            <div className="app-brand-text">
              <h1 className="app-title">Slack Export</h1>
              <p className="app-tagline">Export your DMs and private channels</p>
            </div>
          </div>
          <nav className="tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === TAB.DM_MANUAL}
          className={`tab tab-dm ${activeTab === TAB.DM_MANUAL ? 'tab-active' : ''}`}
          onClick={() => { setActiveTab(TAB.DM_MANUAL); setError(''); setAllDmsError(''); setPrivateChannelsError('') }}
        >
          <DmIcon size={20} className="tab-icon" />
          <span>DM Manual</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === TAB.ALL_DMS}
          className={`tab tab-dm ${activeTab === TAB.ALL_DMS ? 'tab-active' : ''}`}
          onClick={() => { setActiveTab(TAB.ALL_DMS); setError(''); setAllDmsError(''); setPrivateChannelsError('') }}
        >
          <DmAllIcon size={20} className="tab-icon" />
          <span>All DM Export</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === TAB.PRIVATE_CHANNEL_MANUAL}
          className={`tab tab-private ${activeTab === TAB.PRIVATE_CHANNEL_MANUAL ? 'tab-active' : ''}`}
          onClick={() => { setActiveTab(TAB.PRIVATE_CHANNEL_MANUAL); setPrivateChannelsPage(PAGE_PRIVATE.CHANNEL_DETAILS); setError(''); setAllDmsError(''); setPrivateChannelsError('') }}
        >
          <PrivateChannelIcon size={20} className="tab-icon" />
          <span>Private Channel Manual</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === TAB.ALL_PRIVATE_CHANNELS}
          className={`tab tab-private ${activeTab === TAB.ALL_PRIVATE_CHANNELS ? 'tab-active' : ''}`}
          onClick={() => { setActiveTab(TAB.ALL_PRIVATE_CHANNELS); setError(''); setAllDmsError(''); setPrivateChannelsError('') }}
        >
          <PrivateChannelAllIcon size={20} className="tab-icon" />
          <span>All Private Channel Export</span>
        </button>
          </nav>
        </div>
      </header>

      <main className="app-content">
        <div className="app-content-inner">
      {activeTab === TAB.DM_MANUAL && (
        <div className="card">
          <section className="flow-section">
            <p className="app-description">Enter Channel IDs (comma-separated). Users and group names are auto-fetched from the channel.</p>
            <div className="field">
              <label htmlFor="dm-channel-ids">One-on-One DM Channel IDs (comma-separated)</label>
              <input id="dm-channel-ids" type="text" value={dmChannelIdsText} onChange={(e) => setDmChannelIdsText(e.target.value)} placeholder="D01234ABCDE, D05678FGHI" disabled={loading || (exportJobExportType === 'manual' && exportJobRunning)} />
            </div>
            <div className="field">
              <label htmlFor="mpim-channel-ids">Group DM Channel IDs (comma-separated)</label>
              <input id="mpim-channel-ids" type="text" value={mpimChannelIdsText} onChange={(e) => setMpimChannelIdsText(e.target.value)} placeholder="G01234ABCDE, G05678FGHI" disabled={loading || (exportJobExportType === 'manual' && exportJobRunning)} />
            </div>
            <div className="field-row">
              <div className="field">
                <label htmlFor="from-date">From Date</label>
                <input id="from-date" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} disabled={loading || (exportJobExportType === 'manual' && exportJobRunning)} />
              </div>
              <div className="field">
                <label htmlFor="to-date">To Date</label>
                <input id="to-date" type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} disabled={loading || (exportJobExportType === 'manual' && exportJobRunning)} />
              </div>
            </div>
            {exportJobExportType === 'manual' && exportJobId && (
              <div className="export-job-status" style={{ marginBottom: '1rem' }}>
                {exportJobStatus === 'PENDING' || exportJobStatus === 'PROCESSING' ? (
                  <div className="download-progress" role="status" aria-live="polite">
                    <div className="download-progress-bar download-progress-bar-indeterminate">
                      <div className="download-progress-fill" />
                    </div>
                    <p className="download-progress-text">Export is being prepared…</p>
                    <button type="button" className="secondary-btn" onClick={clearExportJob} style={{ marginTop: 8 }}>Cancel export</button>
                  </div>
                ) : exportJobStatus === 'COMPLETED' ? (
                  <div className="message message-success" role="status" style={{ marginBottom: 8 }}>Export ready. Click below to download.</div>
                ) : exportJobStatus === 'FAILED' ? (
                  <div className="message message-error" role="alert" style={{ marginBottom: 8 }}>{exportJobError || 'Export failed.'}</div>
                ) : null}
              </div>
            )}
            {loading && !exportJobId && (
              <div className="download-progress" role="status" aria-live="polite">
                <div className="download-progress-bar download-progress-bar-indeterminate">
                  <div className="download-progress-fill" />
                </div>
                <p className="download-progress-text">Starting export…</p>
              </div>
            )}
            {error && <div className="message message-error" role="alert">{error}</div>}
            {success && <div className="message message-success" role="status">{success}</div>}
            <div className="button-row">
              {exportJobExportType === 'manual' && (exportJobStatus === 'COMPLETED' || exportJobStatus === 'FAILED') ? (
                <>
                  {exportJobStatus === 'COMPLETED' && (
                    <button type="button" className="submit-btn" onClick={handleDownloadFromJob}>Download ZIP</button>
                  )}
                  <button type="button" className="secondary-btn" onClick={clearExportJob}>
                    {exportJobStatus === 'FAILED' ? 'Clear and start new export' : 'Start new export'}
                  </button>
                </>
              ) : (
                <button type="button" className="submit-btn" onClick={handleDownload} disabled={loading || (exportJobExportType === 'manual' && exportJobRunning)}>
                  {loading ? 'Starting…' : (exportJobExportType === 'manual' && exportJobRunning ? 'Preparing…' : 'Download ZIP')}
                </button>
              )}
            </div>
          </section>
        </div>
      )}

      {activeTab === TAB.ALL_DMS && (
        <div className="card">
          <section className="flow-section">
            <p className="app-description">Export all your DM and group DM channels. Uses the token from backend config.</p>

            <div className="field" style={{ marginBottom: '1rem' }}>
              <button
                type="button"
                className="secondary-btn"
                onClick={handleCheckDmCount}
                disabled={allDmsCountLoading || allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
              >
                {allDmsCountLoading ? 'Fetching…' : (exportJobExportType === 'all-dms' && exportJobRunning ? 'Export in progress…' : 'Check number of DM and group DM')}
              </button>
            </div>
            {allDmsCountError && <div className="message message-error" role="alert">{allDmsCountError}</div>}
            {allDmsCountResult && (
              <div className="message message-success" role="status" style={{ marginBottom: '1rem' }}>
                <strong>Unique total: {allDmsCountResult.uniqueTotalCount}</strong>
                {' '}({allDmsCountResult.oneOnOneCount} one-on-one DM{allDmsCountResult.oneOnOneCount !== 1 ? 's' : ''}, {allDmsCountResult.groupDmCount} group DM{allDmsCountResult.groupDmCount !== 1 ? 's' : ''}).
                <br />
                From first token (admin if set): {allDmsCountResult.fromFirstToken} · From second token (user): {allDmsCountResult.fromSecondToken}. Duplicates merged.
              </div>
            )}

            <div className="export-mode-section">
              <p className="field-label">Export mode</p>
              <div className="radio-group">
                <label className="radio-option">
                  <input
                    type="radio"
                    name="allDmsMode"
                    value="FULL"
                    checked={allDmsExportMode === 'FULL'}
                    onChange={() => setAllDmsExportMode('FULL')}
                    disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
                  />
                  <span>Export Full History</span>
                </label>
                <label className="radio-option">
                  <input
                    type="radio"
                    name="allDmsMode"
                    value="CUSTOM"
                    checked={allDmsExportMode === 'CUSTOM'}
                    onChange={() => setAllDmsExportMode('CUSTOM')}
                    disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
                  />
                  <span>Export Specific Date Range</span>
                </label>
              </div>
            </div>

            {allDmsExportMode === 'CUSTOM' && (
              <div className="date-range-section">
                <div className="field">
                  <label htmlFor="all-dms-from-date">From Date</label>
                  <input
                    id="all-dms-from-date"
                    type="date"
                    value={allDmsFromDate}
                    onChange={(e) => setAllDmsFromDate(e.target.value)}
                    disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
                  />
                </div>
                <div className="field">
                  <label htmlFor="all-dms-to-date">To Date</label>
                  <input
                    id="all-dms-to-date"
                    type="date"
                    value={allDmsToDate}
                    onChange={(e) => setAllDmsToDate(e.target.value)}
                    disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
                  />
                </div>
              </div>
            )}

            {exportJobExportType === 'all-dms' && exportJobId && (
              <div className="export-job-status" style={{ marginBottom: '1rem' }}>
                {exportJobStatus === 'PENDING' || exportJobStatus === 'PROCESSING' ? (
                  <div className="download-progress" role="status" aria-live="polite">
                    <div className="download-progress-bar download-progress-bar-indeterminate">
                      <div className="download-progress-fill" />
                    </div>
                    <p className="download-progress-text">Export is being prepared… You can close this page and come back later.</p>
                    <button type="button" className="secondary-btn" onClick={clearExportJob} style={{ marginTop: 8 }}>
                      Cancel export
                    </button>
                  </div>
                ) : exportJobStatus === 'COMPLETED' ? (
                  <div className="message message-success" role="status" style={{ marginBottom: 8 }}>
                    Export ready. Click below to download.
                  </div>
                ) : exportJobStatus === 'FAILED' ? (
                  <div className="message message-error" role="alert" style={{ marginBottom: 8 }}>
                    {exportJobError || 'Export failed.'}
                  </div>
                ) : null}
              </div>
            )}
            {allDmsLoading && !exportJobId && (
              <div className="download-progress" role="status" aria-live="polite">
                <div className="download-progress-bar download-progress-bar-indeterminate">
                  <div className="download-progress-fill" />
                </div>
                <p className="download-progress-text">Starting export…</p>
              </div>
            )}
            {allDmsError && <div className="message message-error" role="alert">{allDmsError}</div>}
            {allDmsSuccess && <div className="message message-success" role="status">{allDmsSuccess}</div>}
            <div className="button-row">
              {exportJobExportType === 'all-dms' && (exportJobStatus === 'COMPLETED' || exportJobStatus === 'FAILED') ? (
                <>
                  {exportJobStatus === 'COMPLETED' && (
                    <button type="button" className="submit-btn" onClick={handleDownloadFromJob}>
                      Download ZIP
                    </button>
                  )}
                  <button type="button" className="secondary-btn" onClick={clearExportJob}>
                    {exportJobStatus === 'FAILED' ? 'Clear and start new export' : 'Start new export'}
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="submit-btn"
                  onClick={handleAllDmsDownload}
                  disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
                >
                  {allDmsLoading ? 'Starting…' : (exportJobExportType === 'all-dms' && exportJobRunning ? 'Preparing…' : 'Download ZIP')}
                </button>
              )}
              <button
                type="button"
                className="secondary-btn"
                onClick={handleDownloadLastExport}
                disabled={allDmsLoading || (exportJobExportType === 'all-dms' && exportJobRunning)}
              >
                Download last completed export
              </button>
            </div>
            <p className="field-label" style={{ marginTop: 8, fontSize: '0.8125rem', color: '#6b7280' }}>
              Export runs in the background. When ready, click &quot;Download ZIP&quot; above; or use &quot;Download last completed export&quot; as a fallback.
            </p>
          </section>
        </div>
      )}

      {activeTab === TAB.PRIVATE_CHANNEL_MANUAL && (
        <>
          <div className="card">
              <section className="flow-section">
                {privateChannelsPage === PAGE_PRIVATE.CHANNEL_DETAILS && (
                  <>
                    <p className="app-description">Enter Channel IDs (comma-separated). Channel names are auto-fetched.</p>
                    <div className="field">
                      <label htmlFor="private-channel-ids">Private Channel IDs (comma-separated)</label>
                      <input
                        id="private-channel-ids"
                        type="text"
                        value={privateChannelIdsText}
                        onChange={(e) => setPrivateChannelIdsText(e.target.value)}
                        placeholder="C01234ABCDE, C05678FGHI"
                        disabled={privateChannelsLoading || privateChannelsJobRunning}
                      />
                    </div>
                    {privateChannelsError && <div className="message message-error" role="alert">{privateChannelsError}</div>}
                    <div className="button-row">
                      <button type="button" className="submit-btn" onClick={handlePrivateChannelsDetailsNext} disabled={privateChannelsLoading || privateChannelsJobRunning}>Next</button>
                    </div>
                  </>
                )}

                {privateChannelsPage === PAGE_PRIVATE.DATE_DOWNLOAD && (
                  <>
                    <p className="app-description">Set From Date and To Date, then download the ZIP.</p>
                    <div className="field-row">
                      <div className="field">
                        <label htmlFor="private-channels-manual-from-date">From Date</label>
                        <input
                          id="private-channels-manual-from-date"
                          type="date"
                          value={privateChannelsFromDate}
                          onChange={(e) => setPrivateChannelsFromDate(e.target.value)}
                          disabled={privateChannelsLoading || privateChannelsJobRunning}
                        />
                      </div>
                      <div className="field">
                        <label htmlFor="private-channels-manual-to-date">To Date</label>
                        <input
                          id="private-channels-manual-to-date"
                          type="date"
                          value={privateChannelsToDate}
                          onChange={(e) => setPrivateChannelsToDate(e.target.value)}
                          disabled={privateChannelsLoading || privateChannelsJobRunning}
                        />
                      </div>
                    </div>
                    {(exportJobExportType === 'private-channels' || exportJobExportType === 'private-channels-manual') && exportJobId && (
                      <div className="export-job-status" style={{ marginBottom: '1rem' }}>
                        {exportJobStatus === 'PENDING' || exportJobStatus === 'PROCESSING' ? (
                          <div className="download-progress" role="status" aria-live="polite">
                            <div className="download-progress-bar download-progress-bar-indeterminate">
                              <div className="download-progress-fill" />
                            </div>
                            <p className="download-progress-text">Export is being prepared… .</p>
                            <button type="button" className="secondary-btn" onClick={clearExportJob} style={{ marginTop: 8 }}>Cancel export</button>
                          </div>
                        ) : exportJobStatus === 'COMPLETED' ? (
                          <div className="message message-success" role="status" style={{ marginBottom: 8 }}>Export ready. Click below to download.</div>
                        ) : exportJobStatus === 'FAILED' ? (
                          <div className="message message-error" role="alert" style={{ marginBottom: 8 }}>{exportJobError || 'Export failed.'}</div>
                        ) : null}
                      </div>
                    )}
                    {privateChannelsLoading && !exportJobId && (
                      <div className="download-progress" role="status" aria-live="polite">
                        <div className="download-progress-bar download-progress-bar-indeterminate">
                          <div className="download-progress-fill" />
                        </div>
                        <p className="download-progress-text">Starting export…</p>
                      </div>
                    )}
                    {privateChannelsError && <div className="message message-error" role="alert">{privateChannelsError}</div>}
                    {privateChannelsSuccess && <div className="message message-success" role="status">{privateChannelsSuccess}</div>}
                    <div className="button-row">
                      <button type="button" className="secondary-btn" onClick={goBackPrivateChannels} disabled={privateChannelsLoading || privateChannelsJobRunning}>Back</button>
                      {(exportJobExportType === 'private-channels' || exportJobExportType === 'private-channels-manual') && (exportJobStatus === 'COMPLETED' || exportJobStatus === 'FAILED') ? (
                        <>
                          {exportJobStatus === 'COMPLETED' && (
                            <button type="button" className="submit-btn" onClick={handleDownloadFromJob}>Download ZIP</button>
                          )}
                          <button type="button" className="secondary-btn" onClick={clearExportJob}>
                            {exportJobStatus === 'FAILED' ? 'Clear and start new export' : 'Start new export'}
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          className="submit-btn"
                          onClick={handlePrivateChannelsManualDownload}
                          disabled={privateChannelsLoading || privateChannelsJobRunning}
                        >
                          {privateChannelsLoading ? 'Starting…' : (privateChannelsJobRunning ? 'Preparing…' : 'Download ZIP')}
                        </button>
                      )}
                    </div>
                  </>
                )}
              </section>
            </div>
        </>
      )}

      {activeTab === TAB.ALL_PRIVATE_CHANNELS && (
        <div className="card">
          <section className="flow-section">
            <p className="app-description">Export all private channels using dual token (admin + user). Uses tokens from backend config.</p>

            <div className="export-mode-section">
              <p className="field-label">Export mode</p>
              <div className="radio-group">
                <label className="radio-option">
                  <input
                    type="radio"
                    name="privateChannelsMode"
                    value="FULL"
                    checked={privateChannelsExportMode === 'FULL'}
                    onChange={() => setPrivateChannelsExportMode('FULL')}
                    disabled={privateChannelsLoading || privateChannelsJobRunning}
                  />
                  <span>Entire History</span>
                </label>
                <label className="radio-option">
                  <input
                    type="radio"
                    name="privateChannelsMode"
                    value="CUSTOM"
                    checked={privateChannelsExportMode === 'CUSTOM'}
                    onChange={() => setPrivateChannelsExportMode('CUSTOM')}
                    disabled={privateChannelsLoading || privateChannelsJobRunning}
                  />
                  <span>Date Range</span>
                </label>
              </div>
            </div>

            {privateChannelsExportMode === 'CUSTOM' && (
              <div className="date-range-section">
                <div className="field">
                  <label htmlFor="private-channels-from-date">From Date</label>
                  <input
                    id="private-channels-from-date"
                    type="date"
                    value={privateChannelsFromDate}
                    onChange={(e) => setPrivateChannelsFromDate(e.target.value)}
                    disabled={privateChannelsLoading || privateChannelsJobRunning}
                  />
                </div>
                <div className="field">
                  <label htmlFor="private-channels-to-date">To Date</label>
                  <input
                    id="private-channels-to-date"
                    type="date"
                    value={privateChannelsToDate}
                    onChange={(e) => setPrivateChannelsToDate(e.target.value)}
                    disabled={privateChannelsLoading || privateChannelsJobRunning}
                  />
                </div>
              </div>
            )}

            {(exportJobExportType === 'private-channels' || exportJobExportType === 'private-channels-manual') && exportJobId && (
              <div className="export-job-status" style={{ marginBottom: '1rem' }}>
                {exportJobStatus === 'PENDING' || exportJobStatus === 'PROCESSING' ? (
                  <div className="download-progress" role="status" aria-live="polite">
                    <div className="download-progress-bar download-progress-bar-indeterminate">
                      <div className="download-progress-fill" />
                    </div>
                    <p className="download-progress-text">Export is being prepared… You can close this page and come back later.</p>
                    <button type="button" className="secondary-btn" onClick={clearExportJob} style={{ marginTop: 8 }}>
                      Cancel export
                    </button>
                  </div>
                ) : exportJobStatus === 'COMPLETED' ? (
                  <div className="message message-success" role="status" style={{ marginBottom: 8 }}>
                    Export ready. Click below to download.
                  </div>
                ) : exportJobStatus === 'FAILED' ? (
                  <div className="message message-error" role="alert" style={{ marginBottom: 8 }}>
                    {exportJobError || 'Export failed.'}
                  </div>
                ) : null}
              </div>
            )}
            {privateChannelsLoading && !exportJobId && (
              <div className="download-progress" role="status" aria-live="polite">
                <div className="download-progress-bar download-progress-bar-indeterminate">
                  <div className="download-progress-fill" />
                </div>
                <p className="download-progress-text">Starting export…</p>
              </div>
            )}
            {privateChannelsError && <div className="message message-error" role="alert">{privateChannelsError}</div>}
            {privateChannelsSuccess && <div className="message message-success" role="status">{privateChannelsSuccess}</div>}
            <div className="button-row">
              <button type="button" className="secondary-btn" onClick={() => setActiveTab(TAB.PRIVATE_CHANNEL_MANUAL)} disabled={privateChannelsLoading || privateChannelsJobRunning}>
                Back
              </button>
              {(exportJobExportType === 'private-channels' || exportJobExportType === 'private-channels-manual') && (exportJobStatus === 'COMPLETED' || exportJobStatus === 'FAILED') ? (
                <>
                  {exportJobStatus === 'COMPLETED' && (
                    <button type="button" className="submit-btn" onClick={handleDownloadFromJob}>
                      Download ZIP
                    </button>
                  )}
                  <button type="button" className="secondary-btn" onClick={clearExportJob}>
                    {exportJobStatus === 'FAILED' ? 'Clear and start new export' : 'Start new export'}
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="submit-btn"
                  onClick={handlePrivateChannelsDownload}
                  disabled={privateChannelsLoading || privateChannelsJobRunning}
                >
                  {privateChannelsLoading ? 'Starting…' : (privateChannelsJobRunning ? 'Preparing…' : 'Download ZIP')}
                </button>
              )}
            </div>
            <p className="field-label" style={{ marginTop: 8, fontSize: '0.8125rem', color: '#6b7280' }}>
              Export runs in the background. ZIP includes groups.json, users.json, channels.json, and date files per channel.
            </p>
          </section>
        </div>
      )}
        </div>
      </main>
    </div>
  )
}

export default App
