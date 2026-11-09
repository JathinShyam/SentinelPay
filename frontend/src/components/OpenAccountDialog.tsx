import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, createAccount } from '../api/client';
import { parseAmount } from '../format';
import { ErrorNotice } from './ui';
import { CloseIcon } from './icons';

const CURRENCIES = [
  { code: 'INR', symbol: '₹', label: 'Indian rupee' },
  { code: 'USD', symbol: '$', label: 'US dollar' },
  { code: 'EUR', symbol: '€', label: 'Euro' },
];

export function OpenAccountDialog({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const queryClient = useQueryClient();
  const [currency, setCurrency] = useState('INR');
  const [deposit, setDeposit] = useState('0');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
  }, []);

  useEffect(() => {
    const element = dialog.current;
    if (!element) {
      return;
    }
    const handleClose = () => onClose();
    element.addEventListener('close', handleClose);
    return () => element.removeEventListener('close', handleClose);
  }, [onClose]);

  function close() {
    dialog.current?.close();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const trimmed = deposit.trim();
    let minor = 0;
    if (trimmed !== '' && trimmed !== '0' && trimmed !== '0.00') {
      const parsed = parseAmount(trimmed);
      if ('error' in parsed) {
        setError(parsed.error);
        return;
      }
      minor = parsed.minor;
    }

    setPending(true);
    try {
      await createAccount(currency, minor);
      await queryClient.invalidateQueries();
      close();
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Could not open the account.');
    } finally {
      setPending(false);
    }
  }

  return (
    <dialog
      ref={dialog}
      className="detail dialog--compact"
      aria-labelledby="open-account-title"
      onClick={(event) => {
        if (event.target === dialog.current) {
          close();
        }
      }}
    >
      <div className="detail__sheet">
        <header className="detail__head">
          <div>
            <p className="folio">New folio</p>
            <h2 id="open-account-title">Open an account</h2>
          </div>
          <button type="button" className="icon-button" onClick={close} aria-label="Close">
            <CloseIcon />
          </button>
        </header>

        <p className="dialog__lede">
          Each account keeps its own currency and statement. You can open more than one.
        </p>

        {error ? <ErrorNotice>{error}</ErrorNotice> : null}

        <form className="form" onSubmit={submit} noValidate>
          <fieldset className="field field--choice">
            <legend className="field__label">Currency</legend>
            <div className="currency-choice">
              {CURRENCIES.map((option) => (
                <label
                  key={option.code}
                  className={`currency-option${
                    currency === option.code ? ' currency-option--active' : ''
                  }`}
                >
                  <input
                    type="radio"
                    name="currency"
                    value={option.code}
                    checked={currency === option.code}
                    onChange={() => setCurrency(option.code)}
                  />
                  <span className="currency-option__symbol" aria-hidden="true">
                    {option.symbol}
                  </span>
                  <span>
                    <span className="currency-option__code">{option.code}</span>
                    <span className="currency-option__name">{option.label}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          <label className="field">
            <span className="field__label">Opening deposit (optional)</span>
            <input
              className="field__input money"
              type="text"
              inputMode="decimal"
              name="deposit"
              value={deposit}
              onChange={(event) => setDeposit(event.target.value)}
              placeholder="0.00"
            />
            <span className="field__hint">
              Funded from the ledger's system reserve account. This is simulated funding
              for the demo environment.
            </span>
          </label>

          <button type="submit" className="btn btn--primary btn--wide" disabled={pending}>
            {pending ? 'Opening…' : 'Open account'}
          </button>
        </form>
      </div>
    </dialog>
  );
}
