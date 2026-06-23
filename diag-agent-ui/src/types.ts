export type EngineType = 'current-runtime'

export interface SessionSummary {
  id: number
  sessionId: string
  title: string
  engine?: string
  userId?: number
  createdAt?: string
  updatedAt?: string
}

export interface SessionMessage {
  type: 'USER' | 'AI' | string
  text: string
}

export interface ChatMessageItem {
  id: string
  role: 'user' | 'assistant'
  content: string
  rawContent?: string
  isTyping?: boolean
  isThinking?: boolean
  isStreaming?: boolean
  loopWarning?: string
}

export interface QuickQuestionItem {
  question: string
}

export interface DiagnosisRecord {
  id: number
  query: string
  conclusion: string
  engine: string
  verified: boolean
  createdAt: string
}

export interface HistoryPageData {
  records: DiagnosisRecord[]
  total: number
}

export interface KnowledgeDocument {
  title: string
  fileName: string
  type: string
  size: string
  updateTime: string
}

export interface KnowledgePageData {
  records: KnowledgeDocument[]
  total: number
}

export interface SkillDocument {
  skillId: string
  title: string
  type: string
  source: string
  size: string
  updateTime: string
  description?: string
}

export interface SkillPageData {
  records: SkillDocument[]
  total: number
}

export interface TopologyNode {
  id: string
  name: string
  status: string
  x: number
  y: number
}

export interface TopologyLink {
  source: string
  target: string
}

export interface TopologyData {
  nodes: TopologyNode[]
  links: TopologyLink[]
}

export interface SystemHealthItem {
  id: string
  name: string
  status: string
  cpu: number
  memory: number
  latency: number
  errorRate: number
}

export interface PromptConfigData {
  success: boolean
  mainPrompt?: string
  error?: string
}

export interface MarkdownTitlesData {
  success: boolean
  titles: string[]
  error?: string
}

export interface NotificationTestData {
  success: boolean
  message?: string
  title?: string
  content?: string
  level?: string
  response?: string
  error?: string
}

export interface RedisHotKeyItem {
  cacheKey: string
  score: number
}

export interface RedisBloomStat {
  kind: string
  sampledSetBits: number
  nullCacheKeys: number
}

export interface RedisOverviewData {
  redisAvailable: boolean
  fallbackActive: boolean
  knowledgeVersion: string
  activeSessionLocks: number
  activeTaskLocks: number
  rateLimitKeys: number
  generatingSessions: number
  cachedAnswerKeys: number
  hotKeys: RedisHotKeyItem[]
  bloomStats: RedisBloomStat[]
}

export interface RedisWarmupResultData {
  requested: number
  warmed: number
  missing: number
  failed: number
  message: string
}

export interface RagRetrievalConfigData {
  vectorTopK: number
  keywordTopK: number
  finalTopK: number
  fusionTopK: number
  rerankTopN: number
  minVectorScore: number
  rerankerConfigured: boolean
}

export interface RagTopResultData {
  title: string
  source: string
  retrievalMode: string
  finalScore: number
  rerankScore: number
  fusionScore: number
  vectorScore: number
  keywordScore: number
}

export interface RagEvaluationCaseData {
  query: string
  expectedTitles?: string[]
  expectedKeywords: string[]
  note: string
}

export interface RagConfigOverrideData {
  vectorTopK?: number
  keywordTopK?: number
  finalTopK?: number
  fusionTopK?: number
  rerankTopN?: number
  minVectorScore?: number
}

export interface RagBaselineSuiteData {
  id: string
  displayName: string
  description: string
  cases: RagEvaluationCaseData[]
}

export interface RagConfigPresetData {
  id: string
  displayName: string
  description: string
  configOverride: RagConfigOverrideData
}

export interface RagEvaluationCaseResultData {
  query: string
  expectedTitles?: string[]
  expectedKeywords: string[]
  note: string
  matchedRank: number
  hitAt1: boolean
  hitAt3: boolean
  topScore: number
  vectorCandidateCount: number
  keywordCandidateCount: number
  topResults: RagTopResultData[]
}

export interface RagEvaluationSummaryData {
  totalCases: number
  hitAt1Count: number
  hitAt3Count: number
  hitAt1Ratio: number
  hitAt3Ratio: number
  meanReciprocalRank: number
}

export interface RagEvaluationResultData {
  evaluationId: string
  evaluationLabel: string
  evaluatedAt: string
  baselineSuiteId: string
  presetId: string
  effectiveConfig: RagRetrievalConfigData
  summary: RagEvaluationSummaryData
  cases: RagEvaluationCaseResultData[]
}

export interface RagGovernanceData {
  activeConfig: RagRetrievalConfigData
  baselineSuites: RagBaselineSuiteData[]
  configPresets: RagConfigPresetData[]
  latestEvaluation: RagEvaluationResultData
  recentEvaluations: RagEvaluationResultData[]
}

export interface GenericMap {
  [key: string]: unknown
}

export interface CapabilitySchemaFieldData {
  name: string
  type: string
  required: boolean
  description: string
}

export interface CapabilityExecutionPolicyData {
  readOnly: boolean
  destructive: boolean
  requiresApproval: boolean
  concurrentSafe: boolean
  policySource: string
  notes: string
}

export interface CapabilityUiHintData {
  displayMode: string
  icon: string
  tags: string[]
  helpText: string
}

export interface CapabilityManifestEntryData {
  id: string
  displayName: string
  kind: string
  source: string
  description: string
  schema: {
    fields: CapabilitySchemaFieldData[]
  }
  executionPolicy: CapabilityExecutionPolicyData
  uiHint: CapabilityUiHintData
  metadata: Record<string, unknown>
}

export interface CapabilityManifestData {
  viewVersion: string
  readOnly: boolean
  totalEntryCount: number
  countsByKind: Record<string, number>
  entries: CapabilityManifestEntryData[]
}

export interface CapabilityRegistryAlignmentData {
  manifestLocalSpecialistCount: number
  runtimeLocalSpecialistCount: number
  manifestLocalToolCount: number
  runtimeLocalToolCount: number
  manifestMcpToolCount: number
  runtimeMcpToolCount: number
  manifestSkillCount: number
  runtimeSkillCount: number
  manifestRemoteSpecialistCount: number
  runtimeRemoteSpecialistCount: number
}

export interface CapabilityManifestOverviewData {
  viewVersion: string
  readOnly: boolean
  totalEntryCount: number
  countsByKind: Record<string, number>
  countsBySource: Record<string, number>
  countsByDisplayMode: Record<string, number>
  countsByTag: Record<string, number>
  entriesWithSchema: number
  schemaFieldCount: number
  approvalRequiredCount: number
  readOnlyCount: number
  destructiveCount: number
  concurrentSafeCount: number
  entriesWithHelpText: number
  entriesWithTags: number
  entriesWithPolicyNotes: number
  registryAlignment: CapabilityRegistryAlignmentData
}

export interface McpRegistryData {
  servers: GenericMap[]
  tools: GenericMap[]
  resources: GenericMap[]
  prompts: GenericMap[]
  capabilitySnapshots: GenericMap[]
}

export interface McpStateData {
  clients: GenericMap[]
  configs: Record<string, GenericMap>
  tools: GenericMap[]
  resources: Record<string, GenericMap[]>
  normalizedNames: Record<string, string>
}

export interface A2aRegistryData {
  agents: GenericMap[]
}

export interface A2aAgentState {
  agentId: string
  name: string
  local: boolean
  status: string
  endpoint: string
  transport: string
  connectionState: string
  skillCount: number
}

export interface A2aStateData {
  agents: A2aAgentState[]
}

export interface SandboxRule {
  action: string
  toolSelector: string
  targetPattern: string
  source: string
  reason: string
  readOnly: boolean
}

export interface SandboxProfileData {
  profileName: string
  readOnlyFilesystem: boolean
  allowReadPaths: string[]
  allowWritePaths: string[]
  denyReadPaths: string[]
  denyWritePaths: string[]
  networkEnabled: boolean
  allowedDomains: string[]
  deniedDomains: string[]
  allowUnsandboxedFallback: boolean
  maxWallClockTime: string
  maxOutputBytes: number
  environmentAllowlist: Record<string, string>
}

export interface SandboxConfigData {
  enabled: boolean
  mode: string
  approvalBackend: string
  autoApproveAskRequests: boolean
  maxAuditRecords: number
  defaultProfile: SandboxProfileData
  rules: SandboxRule[]
}

export interface SandboxAuditRecord {
  executionId: string
  taskId: string
  sessionId: string
  userId: string
  executionMode: string
  toolName: string
  targetType: string
  targetName: string
  workingDirectory: string
  argumentDigest: string
  permissionDecision: string
  approvalStatus: string
  approvalBackend: string
  matchedRule: string
  sandboxProfile: string
  approved: boolean
  success: boolean
  timedOut: boolean
  cancelled: boolean
  exitCode: number
  resultLabel: string
  stdoutDigest: string
  stderrDigest: string
  artifactCount: number
  artifactRefs: SandboxArtifactRefData[]
  createdAt: string
}

export interface SandboxArtifactRefData {
  kind: string
  name: string
  reference: string
  digest: string
}

export interface RuntimeStatusData {
  sessionId: string
  isGenerating: boolean
  remainingTime: number
  hasToolResult?: boolean
}

export interface MemoryMaintenanceRunData {
  trigger: string
  startedAt?: string | null
  finishedAt?: string | null
  processedCount: number
  promotedCount: number
  mergedCount: number
  correctedCount: number
  forgottenCount: number
  rejectedCount: number
  failedCount: number
  handledCandidateIds: string[]
  touchedFiles: boolean
  message: string
}

export interface MemorySummaryData extends GenericMap {
  id?: string
  memoryId?: string
  topic?: string
  summaryType?: string
  content?: string
  version?: number
  updatedAt?: string
}

export interface MemoryCandidateData extends GenericMap {
  id?: string
  memoryId?: string
  sessionId?: string
  topic?: string
  summaryType?: string
  sourceSummaryVersion?: number
  dedupeKey?: string
  status?: string
  proposedOperation?: string
  targetFilePath?: string
  maintenanceNotes?: string
  updatedAt?: string
}

export interface ManagedMemoryFileData {
  path: string
  fileName: string
  size: number
  updateTime: string
  status: string
  dedupeKey: string
  memoryId: string
  topic: string
  lastOperation: string
  latestVersion?: number
  versionCount?: number
  mutationCount?: number
}

export interface ManagedMemoryMutationData extends GenericMap {
  id?: string
  memoryPath?: string
  mutationType?: string
  trigger?: string
  operation?: string
  status?: string
  sourceCandidateId?: string
  memoryId?: string
  sessionId?: string
  dedupeKey?: string
  fromVersion?: number
  toVersion?: number
  fromContentHash?: string
  toContentHash?: string
  summary?: string
  createdAt?: string
}

export interface ManagedMemoryVersionData extends GenericMap {
  id?: string
  memoryPath?: string
  versionNumber?: number
  trigger?: string
  operation?: string
  status?: string
  sourceCandidateId?: string
  memoryId?: string
  sessionId?: string
  dedupeKey?: string
  contentHash?: string
  createdAt?: string
  snapshotContent?: string
  metadata?: Record<string, unknown>
}

export interface MemoryVersionSnapshotData extends GenericMap {
  memoryPath?: string
  versionNumber?: number
  trigger?: string
  operation?: string
  status?: string
  sourceCandidateId?: string
  memoryId?: string
  sessionId?: string
  dedupeKey?: string
  contentHash?: string
  createdAt?: string
  content?: string
  metadata?: Record<string, unknown>
}

export interface MemoryVersionDiffData extends GenericMap {
  memoryPath?: string
  fromVersion?: MemoryVersionSnapshotData
  toVersion?: MemoryVersionSnapshotData
  changedLines?: number
  commonPrefixLines?: number
  commonSuffixLines?: number
  previewLines?: string[]
  summary?: string
}

export interface MemoryRollbackResultData extends GenericMap {
  memoryPath?: string
  targetVersion?: number
  restoredVersion?: number
  reason?: string
  executedAt?: string
}

export interface MemoryCompressionRulesData {
  summaryEnabled: boolean
  summaryType: string
  maxRecentMessages: number
  questionsPerSummary: number
  maxTokens: number
  autoCompactRecentMessages: number
  minimalContextRecentMessages: number
  microCompactThreshold: number
  autoCompactThreshold: number
  minimalContextThreshold: number
  approximateCharsPerToken: number
}

export interface MemoryRecentActivityData {
  eventCount: number
  summaryRefreshCount: number
  contextCompactionCount: number
  minimalContextActivationCount: number
  compactedMessageCount: number
  latestSummaryAt: string
  latestSummaryTopic: string
  latestSummaryVersion: number
  latestCompactionAt: string
  latestCompactionLevel: string
  latestMinimalContextMode: boolean
  latestUsedBudgetTokens: number
  latestMaxBudgetTokens: number
  latestUsageRatio: number
  latestRetainedMessageCount: number
  latestDroppedMessageCount: number
}

export interface MemoryRecentEventData {
  cursor: number
  eventType: string
  sessionId: string
  taskId: string
  memoryId: string
  topic: string
  summaryType: string
  baseVersion: number
  nextVersion: number
  deltaMessageCount: number
  deltaUserQuestionCount: number
  summarizedUserQuestionCount: number
  compactionLevel: string
  minimalContextMode: boolean
  originalMessageCount: number
  retainedMessageCount: number
  droppedMessageCount: number
  usedBudgetTokens: number
  maxBudgetTokens: number
  usageRatio: number
  summaryPresent: boolean
  summary: string
  createdAt: string
}

export interface MemoryOverviewData {
  summaryCount: number
  candidateStatusCounts: Record<string, number>
  managedFileCount: number
  maintenanceRunning: boolean
  maintenanceLastRun: MemoryMaintenanceRunData
  compressionRules: MemoryCompressionRulesData
  recentActivity: MemoryRecentActivityData
  recentEvents: MemoryRecentEventData[]
  summaries: MemorySummaryData[]
  candidates: MemoryCandidateData[]
  managedFiles: ManagedMemoryFileData[]
  recentMutations: ManagedMemoryMutationData[]
}

export interface RuntimeTaskData extends GenericMap {
  taskId?: string
  sessionId?: string
  status?: string
  description?: string
  requestMode?: string
  currentNode?: string
  engine?: string
  lastError?: string
  outputSummary?: string
  createdAt?: string
  updatedAt?: string
  finishedAt?: string
}

export interface SessionLaneEntryData {
  entryId: string
  laneType: string
  taskId?: string
  payloadSummary?: string
  createdAt?: string
}

export interface SessionLaneStateData {
  sessionId: string
  followups: SessionLaneEntryData[]
  steers: SessionLaneEntryData[]
  interrupts: SessionLaneEntryData[]
}

export interface LaneMutationResultData {
  sessionId: string
  laneType: string
  message: string
  laneState: SessionLaneStateData
}

export interface RawExecutionEventData extends GenericMap {
  cursor?: number
  eventId?: string
  eventType?: string
  taskId?: string
  sessionId?: string
  source?: string
  payload?: Record<string, string>
  createdAt?: string
}

export interface ReplayEventEntryData {
  cursor: number
  turnIndex: number
  actionId: string
  actionType: string
  target: string
  phase: string
  status: string
  summary: string
  source: string
  specialistKind: string
  createdAt: string
  kind: string
}

export interface RuntimeReplayEntryData {
  cursor: number
  eventType: string
  phase: string
  status: string
  summary: string
  source: string
  turnIndex: number
  createdAt: string
}

export interface PromptReplayExportData {
  runtimeSection: string
  eventSection: string
  logSection: string
  approvalSection: string
}

export interface ReplayObservabilitySummaryData {
  taskStatus: string
  latestStopReason: string
  rawEventCount: number
  structuredEntryCount: number
  runtimeEntryCount: number
  eventTypeCounts: Record<string, number>
}

export interface ReplayTimelineData {
  sessionId: string
  taskId?: string | null
  task?: RuntimeTaskData | null
  laneState: SessionLaneStateData
  latestCursor: number
  rawEvents: RawExecutionEventData[]
  eventEntries: ReplayEventEntryData[]
  logEntries: ReplayEventEntryData[]
  approvalEntries: ReplayEventEntryData[]
  runtimeEntries: RuntimeReplayEntryData[]
  promptReplay: PromptReplayExportData
  observabilitySummary: ReplayObservabilitySummaryData
}

export interface RuntimeReplayOverviewData {
  recentTasks: RuntimeTaskData[]
  taskStatusCounts: Record<string, number>
  laneEntryCounts: Record<string, number>
  trackedSessionCount: number
}

export interface RuntimeMetricsData {
  trackedTaskCount: number
  trackedSessionCount: number
  taskStatusCounts: Record<string, number>
  laneQueueCounts: Record<string, number>
  laneEventCounts: Record<string, number>
  toolStateCounts: Record<string, number>
  specialistStateCounts: Record<string, number>
  approvalStateCounts: Record<string, number>
  sandboxStateCounts: Record<string, number>
  budgetStatusCounts: Record<string, number>
  memoryStateCounts: Record<string, number>
  capabilityViewVersionCounts: Record<string, number>
  capabilityKindCounts: Record<string, number>
  capabilitySourceCounts: Record<string, number>
  compactionLevelCounts: Record<string, number>
  stopReasonCounts: Record<string, number>
  eventTypeCounts: Record<string, number>
}

export interface ApprovalAuditSummaryData {
  requestedCount: number
  approvedCount: number
  deniedCount: number
  timeoutCount: number
  pendingCount: number
  latestState: string
  latestCursor: number
}

export interface BudgetAuditSummaryData {
  budgetCheckCount: number
  minimalContextTriggerCount: number
  hardStopCount: number
  latestStatus: string
  latestCompactionLevel: string
  latestCursor: number
}

export interface MemoryCompressionAuditSummaryData {
  summaryRefreshCount: number
  contextCompactionCount: number
  minimalContextActivationCount: number
  compactedMessageCount: number
  latestSummaryTopic: string
  latestCompactionLevel: string
  latestCursor: number
}

export interface CapabilityAuditSummaryData {
  snapshotEventCount: number
  latestCapabilityViewVersion: string
  latestCapabilityEntryCount: number
  latestApprovalRequiredCount: number
  latestEntriesWithSchema: number
  latestCountsByKind: Record<string, number>
  latestCountsBySource: Record<string, number>
  latestCursor: number
}

export interface SandboxAuditSummaryData {
  recordCount: number
  approvedCount: number
  deniedCount: number
  successCount: number
  timeoutCount: number
  cancelledCount: number
  artifactRefCount: number
  targetTypeCounts: Record<string, number>
  resultCounts: Record<string, number>
  latestSandboxProfile: string
  latestApprovalBackend: string
  latestTargetType: string
  latestExecutionMode: string
}

export interface SandboxAuditRecordData {
  executionId: string
  sessionId: string
  taskId: string
  userId: string
  executionMode: string
  toolName: string
  targetType: string
  targetName: string
  workingDirectory: string
  argumentDigest: string
  permissionDecision: string
  approvalStatus: string
  approvalBackend: string
  matchedRule: string
  sandboxProfile: string
  approved: boolean
  success: boolean
  timedOut: boolean
  cancelled: boolean
  exitCode: number
  resultLabel: string
  stdoutDigest: string
  stderrDigest: string
  artifactCount: number
  artifactRefs: SandboxArtifactRefData[]
  createdAt: string
}

export interface RuntimeAuditExportData {
  exportVersion: string
  generatedAt: string
  sessionId: string
  taskId: string
  task?: RuntimeTaskData | null
  commonFields: Record<string, string>
  observabilitySummary: ReplayObservabilitySummaryData
  metrics: RuntimeMetricsData
  approvalSummary: ApprovalAuditSummaryData
  budgetSummary: BudgetAuditSummaryData
  memoryCompressionSummary: MemoryCompressionAuditSummaryData
  capabilitySummary: CapabilityAuditSummaryData
  sandboxSummary: SandboxAuditSummaryData
  promptReplay: PromptReplayExportData
  runtimeEntries: RuntimeReplayEntryData[]
  eventEntries: ReplayEventEntryData[]
  logEntries: ReplayEventEntryData[]
  approvalEntries: ReplayEventEntryData[]
  sandboxAuditRecords: SandboxAuditRecordData[]
  rawEvents: RawExecutionEventData[]
}

export interface SkillExecutionPlan extends GenericMap {}
