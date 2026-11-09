export type Role = 'USER' | 'ADMIN';

export interface User {
  userId: string;
  username: string;
  displayName: string;
  role: Role;
}

export interface Account {
  accountId: string;
  ownerId: string;
  currency: string;
  balanceMinor: number;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  accountType: 'USER' | 'SYSTEM';
  createdAt: string;
}

export type TransactionType = 'TRANSFER' | 'DEPOSIT' | 'PAYOUT' | 'FEE' | 'REVERSAL';
export type TransactionStatus = 'POSTED' | 'REVERSED' | 'FAILED';
export type Direction = 'DEBIT' | 'CREDIT';

export interface ActivityItem {
  entryId: string;
  transactionId: string;
  type: TransactionType;
  status: TransactionStatus;
  direction: Direction;
  amountMinor: number;
  currency: string;
  balanceAfterMinor: number;
  counterpartyAccountId?: string;
  description?: string;
  createdAt: string;
}

export interface ActivityPage {
  items: ActivityItem[];
  nextCursor?: string;
}

export interface CurrencyTotal {
  currency: string;
  balanceMinor: number;
}

export interface ProofSummary {
  balanced: boolean;
  accountsChecked: number;
  entryCount: number;
  driftMinor: number;
  checkedAt: string;
}

export interface Overview {
  user: User;
  totals: CurrencyTotal[];
  accounts: Account[];
  recentActivity: ActivityItem[];
  proof: ProofSummary;
}

export interface TransferResult {
  transactionId: string;
  idempotencyKey: string;
  status: string;
  amountMinor: number;
  currency: string;
  sourceBalanceMinorAfter: number;
  destinationBalanceMinorAfter: number;
  createdAt: string;
}

export interface AuditCertificate {
  accountId: string;
  materializedBalanceMinor: number;
  ledgerBalanceMinor: number;
  driftMinor: number;
  entryCount: number;
  transactionsBalanced: boolean;
  checkedAt: string;
}

export interface JournalEntry {
  entryId: string;
  accountId: string;
  direction: Direction;
  amountMinor: number;
  /** Null when the entry belongs to an account this session cannot inspect. */
  runningBalanceMinor?: number | null;
  createdAt: string;
}

export interface TransactionDetail {
  transactionId: string;
  type: TransactionType;
  status: TransactionStatus;
  currency: string;
  description?: string;
  reversalOf?: string;
  reversedBy?: string;
  createdAt: string;
  entries: JournalEntry[];
}

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  errors?: Array<{ field: string; message: string }>;
}
