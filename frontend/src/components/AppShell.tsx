import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';

import { Icon, type IconName } from './Icon';
import { ThemeToggle } from './ThemeToggle';
import './AppShell.scss';

export type NavItem = {
  to: string;
  label: string;
  icon: IconName;
  /** Roles allowed to see this entry. Empty means everyone signed in. */
  roles?: string[];
};

type AppShellProps = {
  nav: NavItem[];
  role?: string;
  userName?: string;
  libraryName: string;
  onToggleTheme: () => void;
  isDark: boolean;
  onLogout?: () => void;
  children: ReactNode;
};

export function AppShell({
  nav,
  role,
  userName,
  libraryName,
  onToggleTheme,
  isDark,
  onLogout,
  children,
}: AppShellProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false);
    window.addEventListener('keydown', onKey);
    // Scrolling behind an open drawer on a phone just hides the menu you opened.
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previous;
    };
  }, [menuOpen]);

  const visible = role ? nav.filter((item) => !item.roles || item.roles.includes(role)) : nav;

  return (
    <div className="shell">
      <a className="shell__skip" href="#main">
        Saltar al contenido
      </a>

      <header className="shell__topbar">
        <button
          type="button"
          className="shell__menu-button"
          aria-label={menuOpen ? 'Cerrar navegacion' : 'Abrir navegacion'}
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
          {role && <span className="shell__role">{role.toLowerCase()}</span>}
          {userName && <span className="shell__user">{userName}</span>}
          <ThemeToggle isDark={isDark} onToggle={onToggleTheme} />
          {onLogout && (
            <button
              type="button"
              className="shell__logout"
              onClick={() => {
                onLogout();
                navigate('/entrar', { replace: true });
              }}
            >
              Salir
            </button>
          )}
        </div>
      </header>

      <div className="shell__body">
        {menuOpen && (
          <button
            type="button"
            className="shell__backdrop"
            aria-label="Cerrar navegacion"
            onClick={() => setMenuOpen(false)}
          />
        )}

        <nav
          className="shell__nav"
          data-open={menuOpen || undefined}
          aria-label="Navegacion principal"
        >
          <ul className="shell__nav-list">
            {visible.map((item) => (
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