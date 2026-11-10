const SYMBOLS: Record<string, string> = {
  INR: '₹',
  USD: '$',
  EUR: '€',
};

/**
 * Formats integer minor units without turning the amount into a float.
 * The grouping locale follows the currency, not the browser.
 */
export function formatMinor(minor: number, currency: string): string {
  const negative = minor < 0;
  const absolute = Math.abs(minor);
  const major = Math.floor(absolute / 100);
  const fraction = String(absolute % 100).padStart(2, '0');
  const locale = currency === 'INR' ? 'en-IN' : 'en-US';
  const grouped = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 0,
  }).format(major);
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  return `${negative ? '−' : ''}${symbol}${grouped}.${fraction}`;
}

export function formatSigned(minor: number, currency: string, direction: 'DEBIT' | 'CREDIT'): string {
  return `${direction === 'DEBIT' ? '−' : '+'}${formatMinor(minor, currency)}`;
}

export function parseAmount(input: string): { minor: number } | { error: string } {
  const cleaned = input.trim().replace(/,/g, '');
  if (cleaned.length === 0) {
    return { error: 'Enter an amount.' };
  }
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) {
    return { error: 'Use digits with up to two decimal places.' };
  }
  const [whole, fraction = ''] = cleaned.split('.');
  if (whole.length > 13) {
    return { error: 'That amount is too large.' };
  }
  const minor = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(minor)) {
    return { error: 'That amount is too large.' };
  }
  if (minor <= 0) {
    return { error: 'Enter an amount greater than zero.' };
  }
  return { minor };
}

export function shortId(id: string): string {
  if (id.length <= 14) {
    return id;
  }
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

const dateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export function formatDate(iso: string): string {
  return dateFormatter.format(new Date(iso));
}

export function formatDateTime(iso: string): string {
  return dateTimeFormatter.format(new Date(iso));
}

export function formatTime(iso: string): string {
  return timeFormatter.format(new Date(iso));
}

export function todayLabel(): string {
  return dateFormatter.format(new Date());
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
}
