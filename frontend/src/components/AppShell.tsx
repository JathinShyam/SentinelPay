import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthProvider';
import { initials } from '../format';
import { Brand } from './Brand';
import {
  ActivityIcon,
  OverviewIcon,
  ProofIcon,
  SendIcon,
  SignOutIcon,
} from './icons';

const NAV = [
  { to: '/wallet', label: 'Overview', icon: OverviewIcon, end: true },
  { to: '/wallet/send', label: 'Send', icon: SendIcon, end: false },
  { to: '/wallet/activity', label: 'Activity', icon: ActivityIcon, end: false },
  { to: '/wallet/proof', label: 'Proof', icon: ProofIcon, end: false },
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

      <aside className="rail">
        <div className="rail__top">
          <Brand light />
          <nav className="rail__nav" aria-label="Wallet sections">
            {NAV.map(({ to, label, icon: Icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) => `rail__link${isActive ? ' rail__link--active' : ''}`}
              >
                <Icon width={19} height={19} />
                <span>{label}</span>
              </NavLink>
            ))}
          </nav>
        </div>

        <div className="rail__account">
          <div className="rail__identity">
            <span className="avatar" aria-hidden="true">
              {initials(user.displayName)}
            </span>
            <span className="rail__who">
              <span className="rail__name">{user.displayName}</span>
              <span className="rail__handle">@{user.username}</span>
            </span>
          </div>
          <p className="rail__session">Session secured · 30 min idle timeout</p>
          <button type="button" className="rail__signout" onClick={signOut}>
            <SignOutIcon width={18} height={18} />
            Sign out
          </button>
        </div>
      </aside>

      <div className="mobile-bar">
        <Brand />
        <div className="mobile-bar__right">
          <span className="avatar avatar--small" aria-hidden="true">
            {initials(user.displayName)}
          </span>
          <button type="button" className="icon-button" onClick={signOut} aria-label="Sign out">
            <SignOutIcon width={19} height={19} />
          </button>
        </div>
      </div>

      <main className="sheet" id="main">
        <div className="sheet__binding" aria-hidden="true" />
        <div className="page">
          <Outlet />
        </div>
      </main>

      <nav className="bottom-nav" aria-label="Wallet sections">
        {NAV.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) => `bottom-nav__link${isActive ? ' bottom-nav__link--active' : ''}`}
          >
            <Icon width={21} height={21} />
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
