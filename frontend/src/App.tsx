import { Navigate, Route, Routes, useParams } from 'react-router-dom';

import { AppShell, LoadingState, type NavItem } from './components';
import { useAuth } from './auth/auth-context';
import { useTheme } from './hooks/useTheme';
import { BookDetail } from './pages/BookDetail';
import { BookForm } from './pages/BookForm';
import { Catalog } from './pages/Catalog';
import { Inventory } from './pages/Inventory';
import { LibraryMap } from './pages/LibraryMap';
import { MyLibrary_ } from './pages/MyLibrary';
import { LibraryData } from './pages/DataTransfer';
import { Loans } from './pages/Loans';
import { ChangePassword } from './pages/ChangePassword';
import { Home } from './pages/Home';
import { Login } from './pages/Login';
import { Users } from './pages/Users';
import { RequireRole } from './routes/guards';

/** Hidden from roles that cannot reach them; the server enforces the same rules. */
const NAV: NavItem[] = [
  { to: '/', label: 'Inicio', icon: 'book' },
  { to: '/catalogo', label: 'Catalogo', icon: 'catalog' },
  { to: '/mi-biblioteca', label: 'Mi biblioteca', icon: 'loans' },
  {
    to: '/inventario',
    label: 'Inventario',
    icon: 'barcode',
    roles: ['BIBLIOTECARIO', 'ADMINISTRATIVO', 'ADMINISTRADOR'],
  },
  {
    to: '/mapa',
    label: 'Mapa',
    icon: 'map',
  },
  {
    to: '/prestamos',
    label: 'Prestamos',
    icon: 'loans',
    roles: ['BIBLIOTECARIO', 'ADMINISTRATIVO', 'ADMINISTRADOR'],
  },
  {
    to: '/cuentas',
    label: 'Cuentas',
    icon: 'users',
    roles: ['ADMINISTRATIVO', 'ADMINISTRADOR'],
  },
  { to: '/ajustes', label: 'Mi contrasena', icon: 'shield', roles: ['ADMINISTRADOR'] },
  {
    to: '/ajustes/datos',
    label: 'Tus datos',
    icon: 'catalog',
    roles: ['ADMINISTRADOR'],
  },
];

export default function App() {
  const { user, loading, logout } = useAuth();
  const { isDark, toggle } = useTheme();

  if (loading) return <LoadingState label="Cargando" rows={3} />;

  if (!user) {
    return (
      <Routes>
        <Route path="/entrar" element={<Login />} />
        <Route path="*" element={<Navigate to="/entrar" replace />} />
      </Routes>
    );
  }

  if (user.mustChangePassword) {
    // Still inside the shell: the user keeps the brand, the theme toggle and a way out.
    return (
      <AppShell
        nav={[]}
        role={user.role}
        userName={user.fullName}
        libraryName="Open Library OS"
        isDark={isDark}
        onToggleTheme={toggle}
        onLogout={() => void logout()}
      >
        <div className="shell__inner">
          <Routes>
            <Route path="/cambiar-contrasena" element={<ChangePassword mandatory />} />
            <Route path="*" element={<Navigate to="/cambiar-contrasena" replace />} />
          </Routes>
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell
      nav={NAV}
      role={user.role}
      userName={user.fullName}
      libraryName="Open Library OS"
      isDark={isDark}
      onToggleTheme={toggle}
      onLogout={() => void logout()}
    >
      <div className="shell__inner">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/catalogo" element={<Catalog />} />
          {/* The menu hides these by role; the route has to hide them too, or a
              reader who types the URL gets a page full of failed requests. */}
          <Route
            path="/inventario"
            element={
              <RequireRole roles={['BIBLIOTECARIO', 'ADMINISTRATIVO', 'ADMINISTRADOR']}>
                <Inventory />
              </RequireRole>
            }
          />
          <Route
            path="/prestamos"
            element={
              <RequireRole roles={['BIBLIOTECARIO', 'ADMINISTRATIVO', 'ADMINISTRADOR']}>
                <Loans />
              </RequireRole>
            }
          />
          <Route path="/mapa" element={<LibraryMap />} />
          <Route path="/mi-biblioteca" element={<MyLibrary_ />} />
          <Route path="/catalogo/nuevo" element={<BookForm />} />
          <Route path="/catalogo/:id" element={<BookDetail />} />
          <Route path="/catalogo/:id/editar" element={<BookFormRoute />} />
          <Route
            path="/cuentas"
            element={
              <RequireRole roles={['ADMINISTRATIVO', 'ADMINISTRADOR']}>
                <Users />
              </RequireRole>
            }
          />
          <Route path="/ajustes" element={<ChangePassword />} />
          <Route path="/ajustes/datos" element={<LibraryData />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </AppShell>
  );
}

/** The edit route reads the id from the path; the "new" route has none. */
function BookFormRoute() {
  const { id } = useParams();
  const bookId = Number(id);
  return Number.isFinite(bookId) ? <BookForm bookId={bookId} /> : <Navigate to="/catalogo" replace />;
}
