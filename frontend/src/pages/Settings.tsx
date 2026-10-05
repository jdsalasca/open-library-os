import { PageHead } from '../components';
import { BackupCard } from '../components/BackupCard';
import { LoanSettings } from '../components/LoanSettings';
import { LibraryData } from './DataTransfer';
import './Settings.scss';

/**
 * Everything about how this library lends, in one place.
 *
 * Before this screen the lending period, the per-reader limit and the renewal
 * cap lived in `app_config` with no way to reach them but a psql shell inside
 * the container. That is exactly the kind of "just run this on the server" that
 * a self-hosted app is supposed to not need.
 */
export function Settings() {
  return (
    <div className="settings">
      <PageHead
        eyebrow="Ajustes"
        title="Reglas y datos"
        lead="Como presta esta biblioteca, y donde estan tus datos. Sin tocar el servidor."
      />
      <LoanSettings />
      <BackupCard />
      <LibraryData embedded />
    </div>
  );
}