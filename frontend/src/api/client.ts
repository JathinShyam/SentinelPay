import type {
  Account,
  ActivityPage,
  AuditCertificate,
  Overview,
  Problem,
  TransactionDetail,
  TransferResult,
  User,
} from './types';

const API = '/api/v1';

let csrfToken: string | null = null;

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly fieldErrors: Array<{ field: string; message: string }>;

  constructor(status: number, problem: Problem | null, fallback: string) {
    super(problem?.detail ?? problem?.title ?? fallback);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem?.code;
    this.fieldErrors = problem?.errors ?? [];
  }
}

async function problemFrom(response: Response): Promise<ApiError> {
  let problem: Problem | null = null;
  try {
    problem = (await response.json()) as Problem;
  } catch {
    // The body may be empty or HTML from a proxy failure.
  }
  return new ApiError(response.status, problem, response.statusText);
}

function notifyUnauthorized(status: number): void {
  if (status === 401) {
    window.dispatchEvent(new Event('sentinelpay:unauthorized'));
  }
}

async function readJson<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API}${path}`, {
    credentials: 'same-origin',
    ...init,
  });
  if (!response.ok) {
    notifyUnauthorized(response.status);
    throw await problemFrom(response);
  }
  return readJson<T>(response);
}

async function ensureCsrf(): Promise<string> {
  if (csrfToken) {
    return csrfToken;
  }
  const response = await fetch(`${API}/auth/csrf`, { credentials: 'same-origin' });
  if (!response.ok) {
    throw await problemFrom(response);
  }
  const body = (await response.json()) as { token: string; headerName: string };
  csrfToken = body.token;
  return body.token;
}

async function refreshCsrf(): Promise<string> {
  csrfToken = null;
  return ensureCsrf();
}

async function mutate<T>(path: string, method: string, body?: unknown): Promise<T> {
  const send = async (token: string) =>
    fetch(`${API}${path}`, {
      method,
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': token,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  let response = await send(await ensureCsrf());
  if (response.status === 403) {
    // CSRF tokens rotate on login and can expire with the session. Refresh once.
    response = await send(await refreshCsrf());
  }
  if (!response.ok) {
    notifyUnauthorized(response.status);
    throw await problemFrom(response);
  }
  if (method === 'POST' && path === '/auth/logout') {
    csrfToken = null;
  }
  return readJson<T>(response);
}

export async function getMe(): Promise<User> {
  return request<User>('/auth/me');
}

export async function login(username: string, password: string): Promise<User> {
  const token = await ensureCsrf();
  const response = await fetch(`${API}/auth/login`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'X-XSRF-TOKEN': token,
    },
    body: new URLSearchParams({ username, password }).toString(),
  });
  if (!response.ok) {
    throw await problemFrom(response);
  }
  const user = await readJson<User>(response);
  // Spring Security rotates the CSRF token on authentication.
  await refreshCsrf();
  return user;
}

export async function register(input: {
  username: string;
  displayName: string;
  password: string;
}): Promise<User> {
  return mutate<User>('/auth/register', 'POST', input);
}

export async function logout(): Promise<void> {
  await mutate<void>('/auth/logout', 'POST');
}

export async function getOverview(): Promise<Overview> {
  return request<Overview>('/me/overview');
}

export async function getAccounts(): Promise<Account[]> {
  return request<Account[]>('/me/accounts');
}

export async function getActivity(accountId: string, cursor?: string): Promise<ActivityPage> {
  const params = new URLSearchParams({ limit: '25' });
  if (cursor) {
    params.set('cursor', cursor);
  }
  return request<ActivityPage>(`/accounts/${accountId}/transactions?${params.toString()}`);
}

export async function getTransaction(transactionId: string): Promise<TransactionDetail> {
  return request<TransactionDetail>(`/transactions/${transactionId}`);
}

export async function getAudit(accountId: string): Promise<AuditCertificate> {
  return request<AuditCertificate>(`/accounts/${accountId}/audit`);
}

export async function createAccount(currency: string, initialDepositMinor: number): Promise<Account> {
  return mutate<Account>('/accounts', 'POST', { currency, initialDepositMinor });
}

export async function createTransfer(input: {
  sourceAccountId: string;
  destinationAccountId: string;
  amountMinor: number;
  currency: string;
  description?: string;
  idempotencyKey: string;
}): Promise<TransferResult> {
  const { idempotencyKey, ...body } = input;
  const send = async (token: string) =>
    fetch(`${API}/transfers`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        'X-XSRF-TOKEN': token,
      },
      body: JSON.stringify(body),
    });

  let response = await send(await ensureCsrf());
  if (response.status === 403) {
    response = await send(await refreshCsrf());
  }
  if (!response.ok) {
    notifyUnauthorized(response.status);
    throw await problemFrom(response);
  }
  return readJson<TransferResult>(response);
}
