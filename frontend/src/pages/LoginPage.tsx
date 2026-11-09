import { useRef, useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { GateLayout } from '../components/GateLayout';
import { ErrorNotice } from '../components/ui';

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/wallet';

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  if (status === 'authenticated') {
    return <Navigate to={from} replace />;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    if (!username.trim()) {
      setError('Enter your username.');
      usernameRef.current?.focus();
      return;
    }
    if (!password) {
      setError('Enter your password.');
      passwordRef.current?.focus();
      return;
    }
    setPending(true);
    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'INVALID_CREDENTIALS') {
        setError('That username and password do not match.');
      } else if (failure instanceof ApiError && failure.code === 'FORBIDDEN') {
        setError('This session could not be verified. Reload the page and try again.');
      } else {
        setError(failure instanceof Error ? failure.message : 'Sign in failed.');
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <GateLayout>
      <div className="gate__form-inner">
        <h2>Sign in</h2>
        <p className="gate__form-lede">
          Use the username you registered. Your session ends after 30 minutes of inactivity.
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
              ref={usernameRef}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={error ? true : undefined}
            />
          </label>

          <label className="field">
            <span className="field__label">Password</span>
            <input
              className="field__input"
              type="password"
              name="password"
              autoComplete="current-password"
              ref={passwordRef}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={error ? true : undefined}
            />
          </label>

          <button type="submit" className="btn btn--primary btn--wide" disabled={pending}>
            {pending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="gate__switch">
          New to SentinelPay? <Link to="/register">Open a ledger</Link>
        </p>
      </div>
    </GateLayout>
  );
}
