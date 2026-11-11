import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { RequireAuth } from './auth/RequireAuth';
import { ActivityPage } from './pages/ActivityPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';
import { ProofPage } from './pages/ProofPage';
import { RegisterPage } from './pages/RegisterPage';
import { SendPage } from './pages/SendPage';

export function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="/wallet" element={<OverviewPage />} />
          <Route path="/wallet/send" element={<SendPage />} />
          <Route path="/wallet/activity" element={<ActivityPage />} />
          <Route path="/wallet/proof" element={<ProofPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
