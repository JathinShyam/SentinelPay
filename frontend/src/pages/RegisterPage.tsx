import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { GateLayout } from '../components/GateLayout';
import { ErrorNotice } from '../components/ui';

const USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;

export function RegisterPage() {
  const { status, register } = useAuth();
  const navigate = useNavigate();

  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to="/wallet" replace />;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});

    const nextFieldErrors: Record<string, string> = {};
    if (!USERNAME_PATTERN.test(username.trim())) {
      nextFieldErrors.username =
        'Use 3–64 letters, digits, dots, underscores or hyphens, starting with a letter or digit.';
    }
    if (!displayName.trim()) {
      nextFieldErrors.displayName = 'Enter the name you want shown on your wallet.';
    }
    if (password.length < 10) {
      nextFieldErrors.password = 'Use at least 10 characters.';
    } else if (password.length > 72) {
      nextFieldErrors.password = 'Use at most 72 characters.';
    }
    if (confirmation !== password) {
      nextFieldErrors.confirmation = 'The two passwords do not match.';
    }
    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      return;
    }

    setPending(true);
    try {
      await register({
        username: username.trim(),
        displayName: displayName.trim(),
        password,
      });
      navigate('/wallet', { replace: true });
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'USERNAME_TAKEN') {
        setFieldErrors({ username: 'That username is already registered.' });
      } else if (failure instanceof ApiError && failure.fieldErrors.length > 0) {
        setFieldErrors(
          Object.fromEntries(failure.fieldErrors.map((item) => [item.field, item.message])),
        );
      } else {
        setError(failure instanceof Error ? failure.message : 'Registration failed.');
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <GateLayout>
      <div className="gate__form-inner">
        <p className="folio">New customer</p>
        <h2>Open your ledger</h2>
        <p className="gate__form-lede">
          One identity, a double-entry account book, and a statement you can verify
          yourself.
        </p>

        {error ? <ErrorNotice>{error}</ErrorNotice> : null}

        <form className="form" onSubmit={submit} noValidate>
          <label className="field">
            <span className="field__label">Username</span>
            <input
              className="field__input"
              type="text"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={fieldErrors.username ? true : undefined}
              aria-describedby={fieldErrors.username ? 'username-error' : undefined}
            />
            {fieldErrors.username ? (
              <span className="field__error" id="username-error">
                {fieldErrors.username}
              </span>
            ) : (
              <span className="field__hint">This is how you sign in.</span>
            )}
          </label>

          <label className="field">
            <span className="field__label">Name on the wallet</span>
            <input
              className="field__input"
              type="text"
              name="displayName"
              autoComplete="name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              aria-invalid={fieldErrors.displayName ? true : undefined}
              aria-describedby={fieldErrors.displayName ? 'display-name-error' : undefined}
            />
            {fieldErrors.displayName ? (
              <span className="field__error" id="display-name-error">
                {fieldErrors.displayName}
              </span>
            ) : null}
          </label>

          <label className="field">
            <span className="field__label">Password</span>
            <input
              className="field__input"
              type="password"
              name="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={fieldErrors.password ? true : undefined}
              aria-describedby={fieldErrors.password ? 'password-error' : 'password-hint'}
            />
            {fieldErrors.password ? (
              <span className="field__error" id="password-error">
                {fieldErrors.password}
              </span>
            ) : (
              <span className="field__hint" id="password-hint">
                At least 10 characters. Use a passphrase you do not use elsewhere.
              </span>
            )}
          </label>

          <label className="field">
            <span className="field__label">Confirm password</span>
            <input
              className="field__input"
              type="password"
              name="confirmation"
              autoComplete="new-password"
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
              aria-invalid={fieldErrors.confirmation ? true : undefined}
              aria-describedby={fieldErrors.confirmation ? 'confirmation-error' : undefined}
            />
            {fieldErrors.confirmation ? (
              <span className="field__error" id="confirmation-error">
                {fieldErrors.confirmation}
              </span>
            ) : null}
          </label>

          <button type="submit" className="btn btn--primary btn--wide" disabled={pending}>
            {pending ? 'Opening your ledger…' : 'Open my ledger'}
          </button>
        </form>

        <p className="gate__switch">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </GateLayout>
  );
}
