import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useTheme } from '../hooks/useTheme';

function setSystemDark(dark: boolean) {
  window.matchMedia = ((query: string) =>
    ({
      matches: query.includes('dark') && dark,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }) as unknown as MediaQueryList) as typeof window.matchMedia;
}

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    setSystemDark(false);
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('follows the OS when the user has not chosen', () => {
    const { result } = renderHook(() => useTheme());

    expect(result.current.isDark).toBe(false);
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('persists an explicit choice over the OS preference', () => {
    setSystemDark(false);
    const { result } = renderHook(() => useTheme());

    act(() => result.current.toggle());

    expect(result.current.isDark).toBe(true);
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('olo.theme')).toBe('dark');
  });
});