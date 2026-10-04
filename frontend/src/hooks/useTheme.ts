import { useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

/** 'system' follows the OS until the user picks explicitly. */
type Preference = Theme | 'system';

const STORAGE_KEY = 'olo.theme';

function read(): Preference {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' ? stored : 'system';
}

function apply(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
}

/** Resolves the stored preference and keeps `data-theme` in sync with the OS. */
export function useTheme() {
  const [preference, setPreference] = useState<Preference>(read);
  const [systemDark, setSystemDark] = useState(
    () => window.matchMedia('(prefers-color-scheme: dark)').matches,
  );

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystemDark(media.matches);
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  const theme: Theme =
    preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;

  useEffect(() => {
    apply(theme);
    if (preference === 'system') {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, preference);
    }
  }, [theme, preference]);

  return {
    theme,
    isDark: theme === 'dark',
    toggle: () => setPreference(theme === 'dark' ? 'light' : 'dark'),
  };
}