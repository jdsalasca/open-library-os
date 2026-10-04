import { Navigate, Route, Routes } from 'react-router-dom';

import { AppShell, type NavItem } from './components';
import { useTheme } from './hooks/useTheme';
import { Home } from './pages/Home';

const NAV: NavItem[] = [{ to: '/', label: 'Inicio', icon: 'book' }];

export default function App() {
  const { isDark, toggle } = useTheme();

  return (
    <AppShell
      nav={NAV}
      libraryName="Open Library OS"
      isDark={isDark}
      onToggleTheme={toggle}
    >
      <div className="shell__inner">
        <Routes>
          <Route path="/" element={<Home libraryName="Open Library OS" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </AppShell>
  );
}