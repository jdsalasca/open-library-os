import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';

import { Icon, type IconName } from './Icon';
import { ThemeToggle } from './ThemeToggle';
import './AppShell.scss';

export type NavItem = {
  to: string;
  label: string;
  icon: IconName;
  /** Minimum role required; see RolePermissions on the backend. */
  roles?: string[];
};

type AppShellProps = {
  nav: NavItem[];
  role?: string;
  libraryName: string;
  onToggleTheme: () => void;
  isDark: boolean;
  children: ReactNode;
};

export function AppShell({
  nav,
  role,
  libraryName,
  onToggleTheme,
  isDark,
  children,
}: AppShellProps) {
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  return (
    <div className="shell">
      <a className="shell__skip" href="#main">
        Saltar al contenido
      </a>

      <header className="shell__topbar">
        <button
          type="button"
          className="shell__menu-button"
          aria-label="Abrir navegacion"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((v) => !v)}
        >
          <Icon name={menuOpen ? 'close' : 'menu'} />
        </button>

        <div className="shell__brand">
          <span className="shell__brand-mark">
            <Icon name="book" size={18} />
          </span>
          <span className="shell__brand-name">{libraryName}</span>
        </div>

        <div className="shell__topbar-end">
          {role && <span className="shell__role">{role}</span>}
          <ThemeToggle isDark={isDark} onToggle={onToggleTheme} />
        </div>
      </header>

      <div className="shell__body">
        <nav
          className="shell__nav"
          data-open={menuOpen || undefined}
          aria-label="Navegacion principal"
        >
          <ul className="shell__nav-list">
            {nav.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    `shell__nav-link${isActive ? ' shell__nav-link--active' : ''}`
                  }
                  onClick={() => setMenuOpen(false)}
                >
                  <Icon name={item.icon} size={17} />
                  <span>{item.label}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <main className="shell__main" id="main">
          {children}
        </main>
      </div>
    </div>
  );
}