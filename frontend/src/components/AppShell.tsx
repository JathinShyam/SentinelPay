import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthProvider';
import { Brand } from './Brand';
import { SignOutIcon } from './icons';

const NAV = [
  { to: '/wallet', label: 'Overview', end: true },
  { to: '/wallet/send', label: 'Send', end: false },
  { to: '/wallet/activity', label: 'Activity', end: false },
  { to: '/wallet/proof', label: 'Proof', end: false },
];

export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [location.pathname]);

  async function signOut() {
    await logout();
    queryClient.clear();
    navigate('/login', { replace: true });
  }

  if (!user) {
    return null;
  }

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <header className="topbar">
        <Brand />

        <nav className="topnav" aria-label="Wallet sections">
          {NAV.map(({ to, label, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `topnav__link${isActive ? ' topnav__link--active' : ''}`
              }
            >
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="topbar__account">
          <span className="identity">
            <span className="identity__name">{user.displayName}</span>
            <span className="identity__handle">@{user.username}</span>
          </span>
          <button type="button" className="signout" onClick={signOut}>
            <SignOutIcon width={17} height={17} />
            <span>Sign out</span>
          </button>
        </div>
      </header>

      <main className="page" id="main">
        <Outlet />
      </main>

      <nav className="bottom-nav" aria-label="Wallet sections">
        {NAV.map(({ to, label, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              `bottom-nav__link${isActive ? ' bottom-nav__link--active' : ''}`
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
